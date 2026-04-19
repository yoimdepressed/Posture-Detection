package com.esw.postureanalyzer.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.util.Log;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

public class PoseLandmarkerHelper {
    private static final String TAG = "PoseLandmarkerHelper";
    public static final int DELEGATE_CPU = 0;
    public static final int DELEGATE_GPU = 1;
    private static final String MODEL_PATH = "pose_landmarker_lite.task";

    private final Context context;
    private final LandmarkerListener listener;
    private PoseLandmarker poseLandmarker;
    private int currentDelegate = DELEGATE_CPU;
    private final Object lock = new Object(); // Synchronization lock
    private volatile boolean isInitialized = false;
    private volatile boolean isProcessing = false; // Track if currently processing a frame
    
    private final PerformanceMonitor performanceMonitor = new PerformanceMonitor("PoseLandmarker");

    public PoseLandmarkerHelper(Context context, LandmarkerListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setupPoseLandmarker() {
        synchronized (lock) {
            try {
                // Clear any existing instance first
                if (poseLandmarker != null) {
                    poseLandmarker.close();
                    poseLandmarker = null;
                }
                
                isInitialized = false;
                
                BaseOptions.Builder baseOptionsBuilder = BaseOptions.builder();
                if (currentDelegate == DELEGATE_GPU) {
                    Log.d(TAG, "Setting up PoseLandmarker with GPU delegate");
                    try {
                        baseOptionsBuilder.setDelegate(Delegate.GPU);
                        Log.d(TAG, "  ✓ GPU delegate set for MediaPipe");
                    } catch (Exception e) {
                        Log.e(TAG, "  ✗ Failed to set GPU delegate, falling back to CPU", e);
                        currentDelegate = DELEGATE_CPU; // Fallback to CPU
                    }
                } else {
                    Log.d(TAG, "Setting up PoseLandmarker with CPU");
                }
                baseOptionsBuilder.setModelAssetPath(MODEL_PATH);

                PoseLandmarker.PoseLandmarkerOptions.Builder optionsBuilder =
                        PoseLandmarker.PoseLandmarkerOptions.builder()
                                .setBaseOptions(baseOptionsBuilder.build())
                                .setRunningMode(RunningMode.LIVE_STREAM)
                                .setResultListener(this::returnLivestreamResult)
                                .setErrorListener(this::returnLivestreamError);
                                
                poseLandmarker = PoseLandmarker.createFromOptions(context, optionsBuilder.build());
                isInitialized = true;
                performanceMonitor.reset();
                
                String delegateStr = (currentDelegate == DELEGATE_GPU) ? "GPU" : "CPU";
                Log.d(TAG, "✓ PoseLandmarker initialized successfully with " + delegateStr);
            } catch (Exception e) {
                isInitialized = false;
                String delegateStr = (currentDelegate == DELEGATE_GPU) ? "GPU" : "CPU";
                Log.e(TAG, "✗ MediaPipe failed to load with " + delegateStr, e);
                
                // Try fallback to CPU if GPU failed
                if (currentDelegate == DELEGATE_GPU) {
                    Log.w(TAG, "→ Attempting fallback to CPU...");
                    currentDelegate = DELEGATE_CPU;
                    setupPoseLandmarker(); // Retry with CPU
                } else {
                    listener.onError("Pose Landmarker failed to initialize. See error logs for details.");
                    Log.e(TAG, "Critical: CPU initialization also failed", e);
                }
            }
        }
    }

    public void detectLiveStream(Bitmap bitmap, int imageRotation) {
        if (!isInitialized || poseLandmarker == null) {
            Log.w(TAG, "PoseLandmarker not initialized, skipping detection");
            return;
        }
        
        if (bitmap == null || bitmap.isRecycled()) {
            Log.w(TAG, "Bitmap is null or recycled, skipping detection");
            return;
        }
        
        synchronized (lock) {
            if (!isInitialized || poseLandmarker == null) {
                return;
            }
            
            try {
                isProcessing = true;
                performanceMonitor.startTotal();
                
                Matrix matrix = new Matrix();
                matrix.postRotate(imageRotation);
                Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, false);
                
                if (rotatedBitmap == null || rotatedBitmap.isRecycled()) {
                    Log.w(TAG, "Rotated bitmap is invalid, skipping detection");
                    isProcessing = false;
                    return;
                }
                
                MPImage mpImage = new BitmapImageBuilder(rotatedBitmap).build();
                
                performanceMonitor.startInference();
                poseLandmarker.detectAsync(mpImage, SystemClock.uptimeMillis());
                
                // Don't recycle rotatedBitmap here as MediaPipe might still be using it
            } catch (IllegalStateException e) {
                Log.e(TAG, "MediaPipe closed during detection", e);
                isInitialized = false;
                isProcessing = false;
            } catch (Exception e) {
                Log.e(TAG, "Error in detectLiveStream", e);
                isProcessing = false;
            }
        }
    }

    private void returnLivestreamResult(PoseLandmarkerResult result, MPImage input) {
        try {
            performanceMonitor.endInference();
            performanceMonitor.endTotal();
            isProcessing = false; // Mark processing complete
            
            if (result == null || input == null) {
                Log.w(TAG, "Null result or input in callback");
                return;
            }
            long inferenceTime = performanceMonitor.getLastTotalMs();
            listener.onResults(new ResultBundle(result, inferenceTime, input.getWidth(), input.getHeight()));
        } catch (Exception e) {
            Log.e(TAG, "Error in returnLivestreamResult", e);
            isProcessing = false;
        }
    }

    private void returnLivestreamError(RuntimeException error) {
        try {
            isProcessing = false; // Mark processing complete on error
            
            if (error != null && error.getMessage() != null) {
                listener.onError(error.getMessage());
            } else {
                listener.onError("Unknown MediaPipe error");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in returnLivestreamError", e);
            isProcessing = false;
        }
    }

    public void clearPoseLandmarker() {
        synchronized (lock) {
            try {
                isInitialized = false;
                if (poseLandmarker != null) {
                    poseLandmarker.close();
                    poseLandmarker = null;
                    Log.d(TAG, "PoseLandmarker cleared");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error clearing PoseLandmarker", e);
                poseLandmarker = null; // Force null even if close fails
            }
        }
    }

    public void setCurrentDelegate(int delegate) {
        if (currentDelegate != delegate) {
            currentDelegate = delegate;
            clearPoseLandmarker();
            setupPoseLandmarker();
        }
    }
    
    public int getCurrentDelegate() {
        return currentDelegate;
    }
    
    /**
     * Get performance statistics
     */
    public String getPerformanceStats() {
        return performanceMonitor.getStats();
    }

    public interface LandmarkerListener {
        void onError(String error);
        void onResults(ResultBundle resultBundle);
    }

    public static class ResultBundle {
        private final PoseLandmarkerResult results;
        private final long inferenceTime;
        private final int inputImageWidth;
        private final int inputImageHeight;

        public ResultBundle(PoseLandmarkerResult results, long inferenceTime, int width, int height) {
            this.results = results;
            this.inferenceTime = inferenceTime;
            this.inputImageWidth = width;
            this.inputImageHeight = height;
        }
        public PoseLandmarkerResult getResults() { return results; }
        public long getInferenceTime() { return inferenceTime; }
        public int getInputImageWidth() { return inputImageWidth; }
        public int getInputImageHeight() { return inputImageHeight; }
    }
}