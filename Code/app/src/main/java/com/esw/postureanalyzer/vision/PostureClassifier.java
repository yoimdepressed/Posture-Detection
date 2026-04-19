package com.esw.postureanalyzer.vision;

import android.content.Context;
import android.util.Log;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;

public class PostureClassifier {
    private static final String TAG = "PostureClassifier";
    
    // Model interpreters
    private Interpreter slouchInterpreter;
    private Interpreter crossLeggedInterpreter;
    private Interpreter leanInterpreter;
    
    // Delegates
    private GpuDelegate gpuDelegate;
    private NnApiDelegate nnApiDelegate;
    
    private DelegateType currentDelegate = DelegateType.CPU;
    private final Context context;
    
    // Performance tracking
    private long lastModelLoadTimeMs = 0;
    private long lastWarmupTimeMs = 0;
    
    // Performance monitors
    private final PerformanceMonitor slouchMonitor = new PerformanceMonitor("Slouch Model");
    private final PerformanceMonitor crossLeggedMonitor = new PerformanceMonitor("CrossLegged Model");
    private final PerformanceMonitor leanMonitor = new PerformanceMonitor("Lean Model");

    // CRITICAL: Replace these with actual values from your scaler.npz file
    // These are placeholder estimates - YOU MUST UPDATE THESE
    private static final float[] CROSS_LEGGED_MEAN = {106.287895f,110.316536f,1.6213433f,1.8441758f,0.4867459f,0.96107554f};
    private static final float[] CROSS_LEGGED_STD = {42.17919f,42.129074f,0.43531278f,0.78367054f,0.49980646f,0.19342752f};

    // Slouching model normalization parameters (3 features)
    // IMPORTANT: Update these with actual values after retraining your slouching model
    // These are placeholders - extract from your training script's StandardScaler
    private static final float[] SLOUCHING_MEAN = new float[]{
           23.053884f, 93.740461f, 95.077424f  // Placeholder - update with: torsoTilt_mean, leftAngle_mean, rightAngle_mean
    };
    private static final float[] SLOUCHING_STD = new float[]{
            16.067474f, 37.209052f, 37.186269f  // Placeholder - update with: torsoTilt_std, leftAngle_std, rightAngle_std
    };

    // Reference resolution from dataset generation
    private static final int REF_WIDTH = 640;
    private static final int REF_HEIGHT = 480;

    public PostureClassifier(Context context) {
        this.context = context;
        initializeModels(DelegateType.CPU);
    }

    /**
     * Initialize all models with the specified delegate
     */
    private void initializeModels(DelegateType delegateType) {
        // Clean up existing resources
        cleanup();

        try {
            Interpreter.Options options = createInterpreterOptions(delegateType);
            
            Log.d(TAG, "Loading models with " + delegateType.getDisplayName() + "...");
            long startTime = System.currentTimeMillis();
            
            slouchInterpreter = new Interpreter(loadModelFile(context, "posture_model.tflite"), options);
            Log.d(TAG, "  ✓ Slouch model loaded");
            
            crossLeggedInterpreter = new Interpreter(loadModelFile(context, "crosslegged.tflite"), options);
            Log.d(TAG, "  ✓ CrossLegged model loaded");
            
            leanInterpreter = new Interpreter(loadModelFile(context, "lean_direction_model.tflite"), options);
            Log.d(TAG, "  ✓ Lean model loaded");
            
            long loadTime = System.currentTimeMillis() - startTime;
            lastModelLoadTimeMs = loadTime;
            
            currentDelegate = delegateType;
            resetPerformanceMonitors();
            
            Log.d(TAG, "✓ All models initialized with " + delegateType.getDisplayName() + " in " + loadTime + "ms");
            
            // Run a test inference to warm up the delegate
            if (delegateType == DelegateType.NNAPI) {
                Log.d(TAG, "Running NNAPI warmup inference...");
                try {
                    float[][] testInput = new float[1][3];
                    float[][] testOutput = new float[1][1];
                    long warmupStart = System.nanoTime();
                    slouchInterpreter.run(testInput, testOutput);
                    long warmupTime = (System.nanoTime() - warmupStart) / 1_000_000;
                    lastWarmupTimeMs = warmupTime;
                    Log.d(TAG, "  → NNAPI warmup completed in " + warmupTime + "ms");
                    if (warmupTime > 100) {
                        Log.w(TAG, "  ⚠ NNAPI warmup took longer than expected - may not be using hardware accelerator");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "  ✗ NNAPI warmup failed", e);
                }
            } else {
                lastWarmupTimeMs = 0; // No warmup for CPU/GPU
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Error initializing models with " + delegateType, e);
            Log.e(TAG, "  → Error type: " + e.getClass().getSimpleName());
            Log.e(TAG, "  → Error message: " + e.getMessage());
            
            // Fallback to CPU if delegate fails
            if (delegateType != DelegateType.CPU) {
                Log.w(TAG, "→ Falling back to CPU due to " + delegateType + " failure");
                try {
                    // Clean up failed delegates before fallback
                    cleanup();
                    currentDelegate = DelegateType.CPU; // Update to CPU before retry
                    Interpreter.Options cpuOptions = new Interpreter.Options();
                    cpuOptions.setNumThreads(4);
                    
                    slouchInterpreter = new Interpreter(loadModelFile(context, "posture_model.tflite"), cpuOptions);
                    crossLeggedInterpreter = new Interpreter(loadModelFile(context, "crosslegged.tflite"), cpuOptions);
                    leanInterpreter = new Interpreter(loadModelFile(context, "lean_direction_model.tflite"), cpuOptions);
                    
                    Log.d(TAG, "✓ Successfully fell back to CPU");
                    resetPerformanceMonitors();
                } catch (Exception cpuError) {
                    Log.e(TAG, "→ CPU fallback also failed - this is critical!", cpuError);
                    throw new RuntimeException("Failed to initialize models even with CPU", cpuError);
                }
            } else {
                Log.e(TAG, "→ CPU initialization failed - this is critical!");
                throw new RuntimeException("Failed to initialize models with CPU", e);
            }
        }
    }

    /**
     * Create interpreter options with the specified delegate
     */
    private Interpreter.Options createInterpreterOptions(DelegateType delegateType) {
        Interpreter.Options options = new Interpreter.Options();

        switch (delegateType) {
            case GPU:
                try {
                    options.setNumThreads(4); // GPU can benefit from multi-threading
                    Log.d(TAG, "Initializing GPU delegate...");
                    
                    CompatibilityList compatList = new CompatibilityList();
                    boolean isCompatible = compatList.isDelegateSupportedOnThisDevice();
                    Log.d(TAG, "  → Compatibility check: " + isCompatible);
                    
                    // Force GPU usage even if compatibility check fails
                    // (Known issue with some Qualcomm devices + TFLite versions)
                    GpuDelegate.Options gpuOptions;
                    if (isCompatible) {
                        gpuOptions = compatList.getBestOptionsForThisDevice();
                        Log.d(TAG, "  → Using best options from CompatibilityList");
                    } else {
                        // Force GPU with default options
                        gpuOptions = new GpuDelegate.Options();
                        gpuOptions.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);
                        gpuOptions.setPrecisionLossAllowed(true); // Allow FP16 for speed
                        Log.w(TAG, "  → Compatibility check failed, forcing GPU with default options");
                        Log.w(TAG, "  → Device has OpenGL ES 3.2 + Adreno 750, should work");
                    }
                    
                    gpuDelegate = new GpuDelegate(gpuOptions);
                    options.addDelegate(gpuDelegate);
                    Log.d(TAG, "✓ GPU delegate enabled successfully");
                } catch (Exception e) {
                    Log.e(TAG, "✗ GPU delegate initialization failed with exception", e);
                    throw new UnsupportedOperationException("GPU not supported - initialization exception: " + e.getMessage());
                }
                break;

            case NNAPI:
                try {
                    NnApiDelegate.Options nnApiOptions = new NnApiDelegate.Options();
                    // Critical settings for hardware acceleration
                    nnApiOptions.setAllowFp16(true);              // Enable FP16 for faster computation
                    nnApiOptions.setUseNnapiCpu(false);           // FORCE hardware acceleration, no CPU fallback
                    nnApiOptions.setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED);
                    nnApiOptions.setMaxNumberOfDelegatedPartitions(3); // Allow all models to be delegated
                    
                    // Try to get accelerator name
                    String acceleratorName = "Unknown";
                    try {
                        // NNAPI will select best available: NPU > DSP > GPU > CPU
                        nnApiDelegate = new NnApiDelegate(nnApiOptions);
                        acceleratorName = nnApiDelegate.toString();
                    } catch (Exception e) {
                        Log.w(TAG, "Could not get NNAPI accelerator info", e);
                    }
                    
                    options.addDelegate(nnApiDelegate);
                    options.setNumThreads(1); // NNAPI handles threading internally
                    
                    Log.d(TAG, "✓ NNAPI delegate enabled");
                    Log.d(TAG, "  → Execution Preference: SUSTAINED_SPEED (hardware acceleration)");
                    Log.d(TAG, "  → FP16 enabled: true");
                    Log.d(TAG, "  → CPU fallback disabled: true");
                    Log.d(TAG, "  → Max delegated partitions: 3");
                    Log.d(TAG, "  → Accelerator: " + acceleratorName);
                    
                    // Device-specific info
                    Log.d(TAG, "  → Device: Qualcomm (Expected: Hexagon DSP/NPU)");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to initialize NNAPI delegate", e);
                    throw new UnsupportedOperationException("NNAPI not supported");
                }
                break;

            case CPU:
            default:
                // CPU uses default options with multi-threading
                options.setNumThreads(4);
                Log.d(TAG, "✓ CPU delegate enabled (4 threads)");
                break;
        }

        return options;
    }

    private MappedByteBuffer loadModelFile(Context context, String modelName) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(context.getAssets().openFd(modelName).getFileDescriptor());
             FileChannel fileChannel = inputStream.getChannel()) {
            long startOffset = context.getAssets().openFd(modelName).getStartOffset();
            long declaredLength = context.getAssets().openFd(modelName).getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
    }

    /**
     * Set the delegate type for all models
     */
    public synchronized void setDelegate(DelegateType delegateType) {
        if (currentDelegate != delegateType) {
            Log.d(TAG, "Switching delegate from " + currentDelegate + " to " + delegateType);
            initializeModels(delegateType);
        }
    }

    /**
     * Get the current delegate type
     */
    public DelegateType getCurrentDelegate() {
        return currentDelegate;
    }

    public synchronized ClassificationResult classify(PoseLandmarkerResult poseResult, int imageWidth, int imageHeight) {
        if (poseResult.landmarks().isEmpty()) {
            return null;
        }
        
        // Safety check: ensure interpreters are initialized
        if (slouchInterpreter == null || crossLeggedInterpreter == null || leanInterpreter == null) {
            Log.w(TAG, "Interpreters not initialized yet, skipping classification");
            return null;
        }
        List<NormalizedLandmark> landmarks = poseResult.landmarks().get(0);

        Log.d(TAG, "Classifying with image dimensions: " + imageWidth + "x" + imageHeight);

        // Extract features using actual image dimensions (matching training data collection)
        float[] slouchFeatures = FeatureExtractor.getSlouchFeatures(landmarks, imageWidth, imageHeight);
        float[] crossLeggedFeatures = FeatureExtractor.getCrossLeggedFeatures(landmarks, imageWidth, imageHeight);
        float[] leaningFeatures = FeatureExtractor.getLeaningFeatures(landmarks, imageWidth, imageHeight);

        // Log raw features before normalization
        Log.d(TAG, String.format("RAW slouch features: [%.2f, %.2f, %.2f]", 
                slouchFeatures[0], slouchFeatures[1], slouchFeatures[2]));

        // TESTING: Try WITHOUT normalization first
        // If your model was trained without StandardScaler, it expects raw features
        // Comment out the normalization below to test
        
        // Apply z-score normalization to slouching features (matching training)
        // TEMPORARILY DISABLED - Testing without normalization
        /*
        for (int i = 0; i < slouchFeatures.length; i++) {
            slouchFeatures[i] = (slouchFeatures[i] - SLOUCHING_MEAN[i]) / (SLOUCHING_STD[i] + 1e-8f);
        }
        */
        
        Log.d(TAG, String.format("Features sent to model: [%.2f, %.2f, %.2f]", 
                slouchFeatures[0], slouchFeatures[1], slouchFeatures[2]));

        // Normalize cross-legged features
        for (int i = 0; i < crossLeggedFeatures.length; i++) {
            crossLeggedFeatures[i] = (crossLeggedFeatures[i] - CROSS_LEGGED_MEAN[i]) / CROSS_LEGGED_STD[i];
        }

        // Run inference
        String slouchStatus = runSlouchInference(slouchFeatures);
        String legsStatus = runCrossLeggedInference(crossLeggedFeatures);
        String leanStatus = runLeaningInference(leaningFeatures);

        return new ClassificationResult(slouchStatus, legsStatus, leanStatus);
    }

    private String runSlouchInference(float[] input) {
        if (slouchInterpreter == null) {
            Log.e(TAG, "Slouch interpreter is NULL!");
            return "N/A";
        }
        try {
            slouchMonitor.startTotal();
            
            float[][] inputArray = new float[1][3];
            System.arraycopy(input, 0, inputArray[0], 0, 3);
            float[][] output = new float[1][1];
            
            slouchMonitor.startInference();
            long startNs = System.nanoTime();
            slouchInterpreter.run(inputArray, output);
            long endNs = System.nanoTime();
            slouchMonitor.endInference();

            float slouchScore = output[0][0];
            
            // Interpretation: score >= 0.5 means good posture (straight/not slouching)
            //                 score < 0.5 means slouching
            boolean isGoodPosture = slouchScore >= 0.5f;
            
            slouchMonitor.endTotal();
            
            long inferenceUs = (endNs - startNs) / 1_000;
            Log.d(TAG, String.format("Slouch [%s]: %.4f -> %s (raw: %d μs)", 
                currentDelegate.getDisplayName(), slouchScore, 
                (isGoodPosture ? "Good" : "Slouch"), inferenceUs));
            
            return isGoodPosture ? "Good Posture" : "Slouching";
        } catch (Exception e) {
            Log.e(TAG, "Slouch inference error", e);
            return "Error";
        }
    }

    private String runCrossLeggedInference(float[] input) {
        if (crossLeggedInterpreter == null) return "N/A";
        try {
            crossLeggedMonitor.startTotal();
            
            float[][] inputArray = new float[1][6];
            System.arraycopy(input, 0, inputArray[0], 0, 6);
            float[][] output = new float[1][1];
            
            crossLeggedMonitor.startInference();
            long startNs = System.nanoTime();
            crossLeggedInterpreter.run(inputArray, output);
            long endNs = System.nanoTime();
            crossLeggedMonitor.endInference();

            float crossLeggedScore = output[0][0];
            boolean isCrossLegged = crossLeggedScore >= 0.5f;
            
            crossLeggedMonitor.endTotal();
            
            long inferenceUs = (endNs - startNs) / 1_000;
            Log.d(TAG, String.format("CrossLegged [%s]: %.4f -> %s (raw: %d μs)", 
                currentDelegate.getDisplayName(), crossLeggedScore,
                (isCrossLegged ? "CrossLegged" : "Uncrossed"), inferenceUs));
            
            return isCrossLegged ? "Cross-legged" : "Normal";
        } catch (Exception e) {
            Log.e(TAG, "CrossLegged inference error", e);
            return "Error";
        }
    }

    private String runLeaningInference(float[] input) {
        if (leanInterpreter == null) return "N/A";
        try {
            leanMonitor.startTotal();
            
            float[][] inputArray = new float[1][9];
            System.arraycopy(input, 0, inputArray[0], 0, 9);
            float[][] output = new float[1][3];
            
            leanMonitor.startInference();
            long startNs = System.nanoTime();
            leanInterpreter.run(inputArray, output);
            long endNs = System.nanoTime();
            leanMonitor.endInference();

            int maxIndex = 0;
            for (int i = 1; i < output[0].length; i++) {
                if (output[0][i] > output[0][maxIndex]) {
                    maxIndex = i;
                }
            }

            // FIXED: Corrected label order to match training (0:left, 1:right, 2:upright)
            String[] labels = {"Left", "Right", "Upright"};
            String result = labels[maxIndex];
            
            leanMonitor.endTotal();
            
            long inferenceUs = (endNs - startNs) / 1_000;
            Log.d(TAG, String.format("Lean [%s]: [%.2f,%.2f,%.2f] -> %s (raw: %d μs)", 
                currentDelegate.getDisplayName(), output[0][0], output[0][1], output[0][2], 
                result, inferenceUs));
            
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Lean inference error", e);
            return "Error";
        }
    }

    /**
     * Get comprehensive performance statistics
     */
    public String getPerformanceStats() {
        return String.format(
            "Delegate: %s\n\n%s\n\n%s\n\n%s",
            currentDelegate.getDisplayName(),
            slouchMonitor.getStats(),
            crossLeggedMonitor.getStats(),
            leanMonitor.getStats()
        );
    }

    /**
     * Get average inference time across all models
     */
    public long getAverageInferenceTimeMs() {
        long slouch = slouchMonitor.getAverageInferenceMs();
        long crossLegged = crossLeggedMonitor.getAverageInferenceMs();
        long lean = leanMonitor.getAverageInferenceMs();
        return (slouch + crossLegged + lean) / 3;
    }

    /**
     * Reset all performance monitors
     */
    public void resetPerformanceMonitors() {
        slouchMonitor.reset();
        crossLeggedMonitor.reset();
        leanMonitor.reset();
    }
    
    /**
     * Get last inference time in microseconds (for PerformanceTracker)
     */
    public long getLastInferenceTimeMicros() {
        // Return average of all three models' last inference times
        long slouch = slouchMonitor.getLastInferenceMs();
        long cross = crossLeggedMonitor.getLastInferenceMs();
        long lean = leanMonitor.getLastInferenceMs();
        return (slouch + cross + lean) / 3;
    }

    /**
     * Get last model load time in milliseconds
     */
    public long getLastModelLoadTimeMs() {
        return lastModelLoadTimeMs;
    }

    /**
     * Get last warmup time in milliseconds
     */
    public long getLastWarmupTimeMs() {
        return lastWarmupTimeMs;
    }
    
    /**
     * Get individual model metrics for Firebase upload
     */
    public java.util.Map<String, Object> getIndividualModelMetrics() {
        java.util.Map<String, Object> allModels = new java.util.HashMap<>();
        
        allModels.put("slouchModel", slouchMonitor.getMetricsMap());
        allModels.put("crossLeggedModel", crossLeggedMonitor.getMetricsMap());
        allModels.put("leanModel", leanMonitor.getMetricsMap());
        allModels.put("delegate", currentDelegate.getDisplayName());
        
        return allModels;
    }

    /**
     * Clean up delegates and interpreters
     */
    private void cleanup() {
        if (slouchInterpreter != null) {
            slouchInterpreter.close();
            slouchInterpreter = null;
        }
        if (crossLeggedInterpreter != null) {
            crossLeggedInterpreter.close();
            crossLeggedInterpreter = null;
        }
        if (leanInterpreter != null) {
            leanInterpreter.close();
            leanInterpreter = null;
        }
        
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        if (nnApiDelegate != null) {
            nnApiDelegate.close();
            nnApiDelegate = null;
        }
    }

    public void close() {
        cleanup();
    }

    public static class ClassificationResult {
        private final String slouchStatus;
        private final String legsStatus;
        private final String leanStatus;

        public ClassificationResult(String slouchStatus, String legsStatus, String leanStatus) {
            this.slouchStatus = slouchStatus;
            this.legsStatus = legsStatus;
            this.leanStatus = leanStatus;
        }

        public String getSlouchStatus() { return slouchStatus; }
        public String getLegsStatus() { return legsStatus; }
        public String getLeanStatus() { return leanStatus; }
    }
}