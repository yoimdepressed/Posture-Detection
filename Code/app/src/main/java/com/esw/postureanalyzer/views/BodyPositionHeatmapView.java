package com.esw.postureanalyzer.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;

/**
 * Displays a body position heatmap showing which areas have posture issues
 * Based on pose landmark analysis
 */
public class BodyPositionHeatmapView extends View {
    
    private Paint bodyPaint;
    private Paint textPaint;
    private Paint heatPaint;
    
    // Body part heat values (0-100, higher = more issues)
    private float headHeat = 0;
    private float neckHeat = 0;
    private float shoulderLeftHeat = 0;
    private float shoulderRightHeat = 0;
    private float backUpperHeat = 0;
    private float backLowerHeat = 0;
    private float hipHeat = 0;

    public BodyPositionHeatmapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        bodyPaint = new Paint();
        bodyPaint.setStyle(Paint.Style.STROKE);
        bodyPaint.setStrokeWidth(3f);
        bodyPaint.setColor(Color.parseColor("#666666"));
        bodyPaint.setAntiAlias(true);

        textPaint = new Paint();
        textPaint.setColor(Color.parseColor("#1A1A1A"));
        textPaint.setTextSize(28f);
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setStyle(Paint.Style.FILL);

        heatPaint = new Paint();
        heatPaint.setStyle(Paint.Style.FILL);
        heatPaint.setAntiAlias(true);
    }

    /**
     * Set body part heat values
     */
    public void setBodyHeatData(float head, float neck, float shoulderL, float shoulderR, 
                                 float backUpper, float backLower, float hip) {
        this.headHeat = head;
        this.neckHeat = neck;
        this.shoulderLeftHeat = shoulderL;
        this.shoulderRightHeat = shoulderR;
        this.backUpperHeat = backUpper;
        this.backLowerHeat = backLower;
        this.hipHeat = hip;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        float centerX = getWidth() / 2f;
        float startY = 80f;
        
        // Draw title
        textPaint.setTextSize(32f);
        textPaint.setStyle(Paint.Style.FILL);
        canvas.drawText("Body Position Issues", centerX, 40f, textPaint);
        textPaint.setTextSize(28f);

        // Head
        drawHeatCircle(canvas, centerX, startY, 40f, headHeat, "Head");
        
        // Neck
        drawHeatCircle(canvas, centerX, startY + 80f, 30f, neckHeat, "Neck");
        
        // Shoulders
        drawHeatCircle(canvas, centerX - 80f, startY + 140f, 35f, shoulderLeftHeat, "L.Shoulder");
        drawHeatCircle(canvas, centerX + 80f, startY + 140f, 35f, shoulderRightHeat, "R.Shoulder");
        
        // Upper Back
        drawHeatRect(canvas, centerX - 60f, startY + 160f, 120f, 60f, backUpperHeat, "Upper Back");
        
        // Lower Back
        drawHeatRect(canvas, centerX - 50f, startY + 230f, 100f, 60f, backLowerHeat, "Lower Back");
        
        // Hips
        drawHeatRect(canvas, centerX - 70f, startY + 300f, 140f, 50f, hipHeat, "Hips");
        
        // Draw legend
        drawLegend(canvas, startY + 400f);
    }

    private void drawHeatCircle(Canvas canvas, float x, float y, float radius, float heat, String label) {
        // Draw heat circle
        heatPaint.setColor(getColorForHeat(heat));
        canvas.drawCircle(x, y, radius, heatPaint);
        
        // Draw border
        bodyPaint.setStrokeWidth(3f);
        canvas.drawCircle(x, y, radius, bodyPaint);
        
        // Draw label
        textPaint.setTextSize(20f);
        canvas.drawText(label, x, y + radius + 30f, textPaint);
        
        // Draw heat value
        textPaint.setTextSize(18f);
        canvas.drawText(String.format("%.0f%%", heat), x, y + 8f, textPaint);
        textPaint.setTextSize(28f);
    }

    private void drawHeatRect(Canvas canvas, float x, float y, float width, float height, 
                              float heat, String label) {
        // Draw heat rectangle
        heatPaint.setColor(getColorForHeat(heat));
        RectF rect = new RectF(x, y, x + width, y + height);
        canvas.drawRoundRect(rect, 10f, 10f, heatPaint);
        
        // Draw border
        bodyPaint.setStrokeWidth(3f);
        canvas.drawRoundRect(rect, 10f, 10f, bodyPaint);
        
        // Draw label
        textPaint.setTextSize(20f);
        canvas.drawText(label, x + width / 2, y + height + 30f, textPaint);
        
        // Draw heat value
        textPaint.setTextSize(18f);
        canvas.drawText(String.format("%.0f%%", heat), x + width / 2, y + height / 2 + 8f, textPaint);
        textPaint.setTextSize(28f);
    }

    private void drawLegend(Canvas canvas, float y) {
        textPaint.setTextSize(24f);
        float centerX = getWidth() / 2f;
        
        canvas.drawText("Issue Frequency", centerX, y, textPaint);
        
        float legendY = y + 30f;
        float legendWidth = 300f;
        float legendHeight = 30f;
        float startX = centerX - legendWidth / 2;
        
        // Draw gradient legend
        for (int i = 0; i < 100; i++) {
            float x = startX + (legendWidth / 100) * i;
            heatPaint.setColor(getColorForHeat(i));
            canvas.drawRect(x, legendY, x + (legendWidth / 100), legendY + legendHeight, heatPaint);
        }
        
        bodyPaint.setStrokeWidth(2f);
        RectF legendRect = new RectF(startX, legendY, startX + legendWidth, legendY + legendHeight);
        canvas.drawRect(legendRect, bodyPaint);
        
        // Labels
        textPaint.setTextSize(20f);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("Low", startX, legendY + legendHeight + 25f, textPaint);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("High", startX + legendWidth, legendY + legendHeight + 25f, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(28f);
    }

    /**
     * Get color based on heat level (0-100)
     * Higher heat = more issues = redder color
     */
    private int getColorForHeat(float heat) {
        if (heat >= 80) {
            return Color.parseColor("#D32F2F"); // Dark Red - Critical
        } else if (heat >= 60) {
            return Color.parseColor("#F44336"); // Red - High
        } else if (heat >= 40) {
            return Color.parseColor("#FF9800"); // Orange - Medium
        } else if (heat >= 20) {
            return Color.parseColor("#FFC107"); // Amber - Low
        } else {
            return Color.parseColor("#4CAF50"); // Green - Minimal
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = 600; // Fixed height for body view
        setMeasuredDimension(width, height);
    }
}
