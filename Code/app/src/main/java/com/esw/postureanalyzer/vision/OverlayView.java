package com.esw.postureanalyzer.vision;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import androidx.annotation.Nullable;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import java.util.List;

public class OverlayView extends View {
    private static final String TAG = "OverlayView";
    
    private PoseLandmarkerResult results;
    private final Paint pointPaint;
    private final Paint linePaint;
    private int imageWidth = 1;
    private int imageHeight = 1;
    private int logCounter = 0;

    public OverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        // Yellow/Gold points for visibility
        pointPaint = new Paint();
        pointPaint.setColor(Color.rgb(255, 215, 0)); // Gold color
        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setStrokeWidth(10f);
        pointPaint.setAntiAlias(true);

        // White lines for connections
        linePaint = new Paint();
        linePaint.setColor(Color.WHITE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(4f);
        linePaint.setAntiAlias(true);
    }

    public void setResults(PoseLandmarkerResult poseLandmarkerResult, int imageHeight, int imageWidth) {
        results = poseLandmarkerResult;
        
        // Check for dimension changes that could cause oscillation
        boolean dimensionsChanged = (this.imageWidth != imageWidth || this.imageHeight != imageHeight);
        
        int oldWidth = this.imageWidth;
        int oldHeight = this.imageHeight;
        
        this.imageHeight = imageHeight;
        this.imageWidth = imageWidth;
        
        // Log when dimensions change - this indicates oscillation cause
        if (dimensionsChanged && oldWidth != 1) { // oldWidth != 1 to skip initial set
            Log.w(TAG, String.format("DIMENSION CHANGE! Image: %dx%d -> %dx%d, View: %dx%d", 
                oldWidth, oldHeight, imageWidth, imageHeight, getWidth(), getHeight()));
            logCounter = 0; // Reset counter to log next few frames
        }
        
        if (logCounter++ % 60 == 0) {
            Log.d(TAG, String.format("Image: %dx%d, View: %dx%d, Aspect: %.2f vs %.2f", 
                imageWidth, imageHeight, getWidth(), getHeight(),
                (float)imageWidth/imageHeight, (float)getWidth()/getHeight()));
        }
        
        invalidate();
    }
    
    /**
     * Clear all landmarks from display
     */
    public void clear() {
        results = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (results == null || results.landmarks().isEmpty()) {
            return;
        }

        int viewWidth = getWidth();
        int viewHeight = getHeight();
        
        if (viewWidth == 0 || viewHeight == 0) {
            return;
        }

        // Calculate scaling to match fitCenter behavior
        // The image is scaled to fit within the view while maintaining aspect ratio
        float imageAspect = (float) imageWidth / imageHeight;
        float viewAspect = (float) viewWidth / viewHeight;
        
        float scaleFactor;
        float offsetX = 0;
        float offsetY = 0;
        
        if (imageAspect > viewAspect) {
            // Image is wider - fit to width, letterbox top/bottom
            scaleFactor = (float) viewWidth / imageWidth;
            offsetY = (viewHeight - imageHeight * scaleFactor) / 2;
        } else {
            // Image is taller - fit to height, letterbox left/right
            scaleFactor = (float) viewHeight / imageHeight;
            offsetX = (viewWidth - imageWidth * scaleFactor) / 2;
        }

        for (List<NormalizedLandmark> normalizedLandmarks : results.landmarks()) {
            // Draw connections first (so they appear behind points)
            drawPoseConnections(canvas, normalizedLandmarks, scaleFactor, offsetX, offsetY);

            // Draw landmarks on top
            for (NormalizedLandmark landmark : normalizedLandmarks) {
                // Convert normalized coordinates to view coordinates
                float x = landmark.x() * imageWidth * scaleFactor + offsetX;
                float y = landmark.y() * imageHeight * scaleFactor + offsetY;
                
                // Only draw if within view bounds
                if (x >= 0 && x <= viewWidth && y >= 0 && y <= viewHeight) {
                    canvas.drawCircle(x, y, 6f, pointPaint);
                }
            }
        }
    }

    private void drawPoseConnections(Canvas canvas, List<NormalizedLandmark> landmarks, 
                                    float scaleFactor, float offsetX, float offsetY) {
        // MediaPipe Pose connections
        int[][] connections = {
                // Face oval
                {0, 1}, {1, 2}, {2, 3}, {3, 7},
                {0, 4}, {4, 5}, {5, 6}, {6, 8},
                // Mouth
                {9, 10},
                // Shoulders
                {11, 12},
                // Left arm
                {11, 13}, {13, 15},
                // Right arm
                {12, 14}, {14, 16},
                // Left hand
                {15, 17}, {15, 19}, {15, 21}, {17, 19},
                // Right hand
                {16, 18}, {16, 20}, {16, 22}, {18, 20},
                // Torso
                {11, 23}, {12, 24}, {23, 24},
                // Left leg
                {23, 25}, {25, 27},
                // Right leg
                {24, 26}, {26, 28},
                // Left foot
                {27, 29}, {27, 31}, {29, 31},
                // Right foot
                {28, 30}, {28, 32}, {30, 32}
        };

        for (int[] connection : connections) {
            if (connection[0] < landmarks.size() && connection[1] < landmarks.size()) {
                NormalizedLandmark start = landmarks.get(connection[0]);
                NormalizedLandmark end = landmarks.get(connection[1]);

                // Convert normalized coordinates to view coordinates
                float startX = start.x() * imageWidth * scaleFactor + offsetX;
                float startY = start.y() * imageHeight * scaleFactor + offsetY;
                float endX = end.x() * imageWidth * scaleFactor + offsetX;
                float endY = end.y() * imageHeight * scaleFactor + offsetY;

                canvas.drawLine(startX, startY, endX, endY, linePaint);
            }
        }
    }
}