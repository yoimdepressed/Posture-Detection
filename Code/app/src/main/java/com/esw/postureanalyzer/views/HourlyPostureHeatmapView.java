package com.esw.postureanalyzer.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays an hourly heatmap showing posture quality throughout the day
 */
public class HourlyPostureHeatmapView extends View {
    private static final int HOURS = 24;
    private static final float BAR_PADDING = 4f;
    
    private Paint barPaint;
    private Paint textPaint;
    private Paint linePaint;
    
    private List<HourData> hourlyData = new ArrayList<>();

    public HourlyPostureHeatmapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        barPaint = new Paint();
        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setAntiAlias(true);

        textPaint = new Paint();
        textPaint.setColor(Color.parseColor("#666666"));
        textPaint.setTextSize(24f);
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);

        linePaint = new Paint();
        linePaint.setColor(Color.parseColor("#E0E0E0"));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(1f);
        linePaint.setAntiAlias(true);
    }

    /**
     * Set hourly data
     * @param data List of hourly posture scores
     */
    public void setHourlyData(List<HourData> data) {
        this.hourlyData = data;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (hourlyData.isEmpty()) {
            drawEmptyState(canvas);
            return;
        }

        float startY = 50f;
        float graphHeight = getHeight() - 100f;
        float barWidth = (getWidth() - (HOURS + 1) * BAR_PADDING) / HOURS;
        
        // Draw grid lines
        for (int i = 0; i <= 4; i++) {
            float y = startY + (graphHeight / 4) * i;
            canvas.drawLine(0, y, getWidth(), y, linePaint);
        }

        // Draw bars
        for (int i = 0; i < hourlyData.size(); i++) {
            HourData data = hourlyData.get(i);
            float x = BAR_PADDING + i * (barWidth + BAR_PADDING);
            
            // Bar height based on score (0-100)
            float barHeight = (data.postureScore / 100f) * graphHeight;
            float y = startY + graphHeight - barHeight;
            
            barPaint.setColor(getColorForScore(data.postureScore));
            canvas.drawRect(x, y, x + barWidth, startY + graphHeight, barPaint);
        }

        // Draw hour labels
        textPaint.setTextSize(20f);
        for (int i = 0; i < hourlyData.size(); i++) {
            if (i % 2 == 0) { // Show every other hour
                float x = BAR_PADDING + i * (barWidth + BAR_PADDING) + barWidth / 2;
                String label = String.format("%02d", hourlyData.get(i).hour);
                canvas.drawText(label, x, startY + graphHeight + 30f, textPaint);
            }
        }

        // Draw Y-axis labels
        textPaint.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 4; i++) {
            float y = startY + (graphHeight / 4) * i;
            int value = 100 - (i * 25);
            canvas.drawText(value + "%", 50f, y + 8f, textPaint);
        }
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(24f);
    }

    private void drawEmptyState(Canvas canvas) {
        textPaint.setTextSize(28f);
        canvas.drawText("No hourly data available", getWidth() / 2f, getHeight() / 2f, textPaint);
        textPaint.setTextSize(24f);
    }

    private int getColorForScore(float score) {
        if (score >= 90) return Color.parseColor("#00C853");
        else if (score >= 75) return Color.parseColor("#64DD17");
        else if (score >= 60) return Color.parseColor("#FFEB3B");
        else if (score >= 40) return Color.parseColor("#FF9800");
        else if (score >= 20) return Color.parseColor("#FF5722");
        else return Color.parseColor("#E0E0E0");
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = 400; // Fixed height for hourly view
        setMeasuredDimension(width, height);
    }

    /**
     * Data class for hourly data
     */
    public static class HourData {
        public int hour; // 0-23
        public float postureScore; // 0-100
        public int sessionCount;

        public HourData(int hour, float postureScore, int sessionCount) {
            this.hour = hour;
            this.postureScore = postureScore;
            this.sessionCount = sessionCount;
        }
    }
}
