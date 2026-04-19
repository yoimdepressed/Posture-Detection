package com.esw.postureanalyzer.reports;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;

import com.esw.postureanalyzer.vision.FirebaseDataRetriever;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Generates PDF reports for posture analytics
 */
public class ReportGenerator {
    private static final String TAG = "ReportGenerator";
    private final Context context;
    private FirebaseDataRetriever dataRetriever;

    public ReportGenerator(Context context) {
        this.context = context;
        this.dataRetriever = new FirebaseDataRetriever();
    }

    public interface ReportCallback {
        void onReportGenerated(File pdfFile);
        void onError(String error);
    }

    /**
     * Generate daily report for yesterday's data
     */
    public void generateDailyReport(ReportCallback callback) {
        // Get yesterday's data
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, -1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long startTime = calendar.getTimeInMillis();

        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        long endTime = calendar.getTimeInMillis();

        String dateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(new Date(startTime));

        generateReport(startTime, endTime, "Daily Report - " + dateStr, callback);
    }

    /**
     * Generate report for custom time period
     */
    public void generateReport(long startTime, long endTime, String title, ReportCallback callback) {
        CountDownLatch latch = new CountDownLatch(1);
        final FirebaseDataRetriever.PostureStatistics[] statsHolder = new FirebaseDataRetriever.PostureStatistics[1];
        final String[] errorHolder = new String[1];

        // Fetch data from Firebase
        dataRetriever.getPostureStatistics(startTime, endTime, new FirebaseDataRetriever.StatisticsCallback() {
            @Override
            public void onStatisticsCalculated(FirebaseDataRetriever.PostureStatistics stats) {
                statsHolder[0] = stats;
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                errorHolder[0] = error;
                latch.countDown();
            }
        });

        // Wait for data retrieval (max 30 seconds)
        new Thread(() -> {
            try {
                if (!latch.await(30, TimeUnit.SECONDS)) {
                    callback.onError("Timeout waiting for data");
                    return;
                }

                if (errorHolder[0] != null) {
                    callback.onError(errorHolder[0]);
                    return;
                }

                if (statsHolder[0] == null || statsHolder[0].totalEntries == 0) {
                    callback.onError("No data available for the selected period");
                    return;
                }

                // Generate PDF
                File pdfFile = createPdfReport(statsHolder[0], title, startTime, endTime);
                if (pdfFile != null) {
                    callback.onReportGenerated(pdfFile);
                } else {
                    callback.onError("Failed to create PDF report");
                }

            } catch (Exception e) {
                Log.e(TAG, "Error generating report", e);
                callback.onError("Error generating report: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Create PDF document with analytics
     */
    private File createPdfReport(FirebaseDataRetriever.PostureStatistics stats, String title,
                                  long startTime, long endTime) {
        try {
            // Create file in cache directory
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
            File pdfFile = new File(context.getCacheDir(), "posture_report_" + timestamp + ".pdf");

            PdfWriter writer = new PdfWriter(pdfFile);
            PdfDocument pdf = new PdfDocument(writer);
            Document document = new Document(pdf);

            // Add title
            Paragraph titlePara = new Paragraph(title)
                    .setFontSize(20)
                    .setBold()
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(10);
            document.add(titlePara);

            // Add date range
            SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
            String dateRange = dateFormat.format(new Date(startTime)) + " - " + 
                              dateFormat.format(new Date(endTime));
            Paragraph datePara = new Paragraph(dateRange)
                    .setFontSize(12)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(20);
            document.add(datePara);

            // Summary section
            document.add(new Paragraph("Summary")
                    .setFontSize(16)
                    .setBold()
                    .setMarginTop(10));

            Table summaryTable = new Table(UnitValue.createPercentArray(new float[]{1, 1}))
                    .setWidth(UnitValue.createPercentValue(100));

            addTableRow(summaryTable, "Total Sessions", String.valueOf(stats.totalEntries));
            addTableRow(summaryTable, "Good Posture", String.format("%.1f%%", stats.getGoodPosturePercentage()));
            addTableRow(summaryTable, "Slouching", String.format("%.1f%%", stats.getSlouchingPercentage()));
            addTableRow(summaryTable, "Cross-legged", String.format("%.1f%%", stats.getCrossLeggedPercentage()));

            document.add(summaryTable);

            // Add chart
            Bitmap chartBitmap = createPostureChart(stats);
            if (chartBitmap != null) {
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                chartBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                byte[] chartBytes = stream.toByteArray();
                
                Image chartImage = new Image(ImageDataFactory.create(chartBytes));
                chartImage.setWidth(UnitValue.createPercentValue(80));
                chartImage.setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.CENTER);
                chartImage.setMarginTop(20);
                document.add(chartImage);
            }

            // Detailed Statistics
            document.add(new Paragraph("Detailed Statistics")
                    .setFontSize(16)
                    .setBold()
                    .setMarginTop(20));

            Table detailTable = new Table(UnitValue.createPercentArray(new float[]{1, 1}))
                    .setWidth(UnitValue.createPercentValue(100));

            addTableRow(detailTable, "Lean Left Count", String.valueOf(stats.leanLeftCount));
            addTableRow(detailTable, "Lean Right Count", String.valueOf(stats.leanRightCount));
            addTableRow(detailTable, "Upright Count", String.valueOf(stats.uprightCount));
            addTableRow(detailTable, "Avg PDJ Score", String.format("%.2f", stats.avgPdj));
            addTableRow(detailTable, "Avg OKS Score", String.format("%.2f", stats.avgOks));
            addTableRow(detailTable, "Avg Inference Time", String.format("%.0f ms", stats.avgInferenceTime));

            document.add(detailTable);

            // Add footer
            document.add(new Paragraph("\nGenerated by Posture Analyzer on " + 
                    new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(new Date()))
                    .setFontSize(10)
                    .setItalic()
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(30)
                    .setFontColor(ColorConstants.GRAY));

            document.close();
            Log.d(TAG, "PDF created successfully: " + pdfFile.getAbsolutePath());
            return pdfFile;

        } catch (Exception e) {
            Log.e(TAG, "Error creating PDF", e);
            return null;
        }
    }

    /**
     * Helper method to add row to table
     */
    private void addTableRow(Table table, String label, String value) {
        table.addCell(new Paragraph(label).setPadding(5));
        table.addCell(new Paragraph(value).setPadding(5).setBold());
    }

    /**
     * Create a pie chart showing posture distribution
     */
    private Bitmap createPostureChart(FirebaseDataRetriever.PostureStatistics stats) {
        try {
            int width = 600;
            int height = 400;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setTextSize(24);
            textPaint.setColor(Color.BLACK);

            // Calculate percentages
            float goodPercent = (float) stats.getGoodPosturePercentage();
            float slouchPercent = (float) stats.getSlouchingPercentage();
            float crossLegPercent = (float) stats.getCrossLeggedPercentage();

            // Draw title
            canvas.drawText("Posture Distribution", width / 2 - 120, 40, textPaint);

            // Draw pie chart
            RectF oval = new RectF(100, 80, 400, 380);
            
            float startAngle = 0;
            
            // Good posture (green)
            paint.setColor(Color.rgb(76, 175, 80));
            float goodSweep = goodPercent * 3.6f;
            canvas.drawArc(oval, startAngle, goodSweep, true, paint);
            startAngle += goodSweep;
            
            // Slouching (red)
            paint.setColor(Color.rgb(244, 67, 54));
            float slouchSweep = slouchPercent * 3.6f;
            canvas.drawArc(oval, startAngle, slouchSweep, true, paint);
            startAngle += slouchSweep;
            
            // Cross-legged (orange)
            paint.setColor(Color.rgb(255, 152, 0));
            float crossSweep = crossLegPercent * 3.6f;
            canvas.drawArc(oval, startAngle, crossSweep, true, paint);

            // Draw legend
            int legendX = 420;
            int legendY = 120;
            int legendSpacing = 50;

            // Good posture
            paint.setColor(Color.rgb(76, 175, 80));
            canvas.drawRect(legendX, legendY, legendX + 30, legendY + 30, paint);
            canvas.drawText(String.format("Good: %.1f%%", goodPercent), legendX + 40, legendY + 22, textPaint);

            // Slouching
            paint.setColor(Color.rgb(244, 67, 54));
            canvas.drawRect(legendX, legendY + legendSpacing, legendX + 30, 
                          legendY + legendSpacing + 30, paint);
            canvas.drawText(String.format("Slouch: %.1f%%", slouchPercent), 
                          legendX + 40, legendY + legendSpacing + 22, textPaint);

            // Cross-legged
            paint.setColor(Color.rgb(255, 152, 0));
            canvas.drawRect(legendX, legendY + legendSpacing * 2, legendX + 30, 
                          legendY + legendSpacing * 2 + 30, paint);
            canvas.drawText(String.format("Cross-leg: %.1f%%", crossLegPercent), 
                          legendX + 40, legendY + legendSpacing * 2 + 22, textPaint);

            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error creating chart", e);
            return null;
        }
    }
}
