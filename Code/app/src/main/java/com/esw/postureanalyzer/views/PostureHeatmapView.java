package com.esw.postureanalyzer.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Custom view that displays a calendar-style heatmap of posture data
 * Similar to GitHub contribution graph
 */
public class PostureHeatmapView extends View {
    private static final int DAYS_TO_SHOW = 30;
    private static final int COLS = 7; // Days per week
    private static final float CELL_PADDING = 4f;
    
    private Paint cellPaint;
    private Paint textPaint;
    private Paint borderPaint;
    
    private List<DayData> dayDataList = new ArrayList<>();
    private float cellSize;

    public PostureHeatmapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        cellPaint = new Paint();
        cellPaint.setStyle(Paint.Style.FILL);
        cellPaint.setAntiAlias(true);

        textPaint = new Paint();
        textPaint.setColor(Color.parseColor("#666666"));
        textPaint.setTextSize(24f);
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);

        borderPaint = new Paint();
        borderPaint.setColor(Color.parseColor("#E0E0E0"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1f);
        borderPaint.setAntiAlias(true);
    }

    /**
     * Set heatmap data
     * @param data List of daily posture scores (0-100)
     */
    public void setData(List<DayData> data) {
        this.dayDataList = data;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (dayDataList.isEmpty()) {
            drawEmptyState(canvas);
            return;
        }

        int rows = (int) Math.ceil(dayDataList.size() / (float) COLS);
        cellSize = (getWidth() - (COLS + 1) * CELL_PADDING) / COLS;
        
        // Draw day labels (S M T W T F S)
        String[] dayLabels = {"S", "M", "T", "W", "T", "F", "S"};
        for (int i = 0; i < COLS; i++) {
            float x = CELL_PADDING + i * (cellSize + CELL_PADDING) + cellSize / 2;
            canvas.drawText(dayLabels[i], x, 30f, textPaint);
        }

        // Draw heatmap cells
        float startY = 50f;
        for (int i = 0; i < dayDataList.size(); i++) {
            int row = i / COLS;
            int col = i % COLS;
            
            float x = CELL_PADDING + col * (cellSize + CELL_PADDING);
            float y = startY + row * (cellSize + CELL_PADDING);
            
            DayData dayData = dayDataList.get(i);
            cellPaint.setColor(getColorForScore(dayData.postureScore));
            
            RectF rect = new RectF(x, y, x + cellSize, y + cellSize);
            canvas.drawRoundRect(rect, 8f, 8f, cellPaint);
            
            // Draw border
            canvas.drawRoundRect(rect, 8f, 8f, borderPaint);
        }

        // Draw legend at bottom
        drawLegend(canvas, startY + rows * (cellSize + CELL_PADDING) + 20f);
    }

    private void drawEmptyState(Canvas canvas) {
        textPaint.setTextSize(32f);
        canvas.drawText("No data available", getWidth() / 2f, getHeight() / 2f, textPaint);
        textPaint.setTextSize(24f);
    }

    private void drawLegend(Canvas canvas, float y) {
        textPaint.setTextSize(20f);
        canvas.drawText("Less", 50f, y, textPaint);
        
        float legendCellSize = 30f;
        float legendX = 120f;
        
        int[] scores = {0, 25, 50, 75, 95};
        for (int i = 0; i < scores.length; i++) {
            cellPaint.setColor(getColorForScore(scores[i]));
            RectF rect = new RectF(
                legendX + i * (legendCellSize + 4f),
                y - 15f,
                legendX + i * (legendCellSize + 4f) + legendCellSize,
                y - 15f + legendCellSize
            );
            canvas.drawRoundRect(rect, 4f, 4f, cellPaint);
            canvas.drawRoundRect(rect, 4f, 4f, borderPaint);
        }
        
        canvas.drawText("More", legendX + 5 * (legendCellSize + 4f) + 20f, y, textPaint);
        textPaint.setTextSize(24f);
    }

    /**
     * Get color based on posture score
     * @param score 0-100 (higher is better)
     * @return Color int
     */
    private int getColorForScore(float score) {
        if (score >= 90) {
            return Color.parseColor("#00C853"); // Excellent - Dark Green
        } else if (score >= 75) {
            return Color.parseColor("#64DD17"); // Good - Light Green
        } else if (score >= 60) {
            return Color.parseColor("#FFEB3B"); // Fair - Yellow
        } else if (score >= 40) {
            return Color.parseColor("#FF9800"); // Poor - Orange
        } else if (score >= 20) {
            return Color.parseColor("#FF5722"); // Bad - Red
        } else {
            return Color.parseColor("#E0E0E0"); // No data - Gray
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int rows = (int) Math.ceil(DAYS_TO_SHOW / (float) COLS);
        cellSize = (width - (COLS + 1) * CELL_PADDING) / COLS;
        int height = (int) (50f + rows * (cellSize + CELL_PADDING) + 100f); // Header + cells + legend
        
        setMeasuredDimension(width, height);
    }

    /**
     * Data class for a single day
     */
    public static class DayData {
        public long timestamp;
        public float postureScore; // 0-100
        public int totalSessions;
        public String date;

        public DayData(long timestamp, float postureScore, int totalSessions, String date) {
            this.timestamp = timestamp;
            this.postureScore = postureScore;
            this.totalSessions = totalSessions;
            this.date = date;
        }
    }
}
