package com.esw.postureanalyzer.performance;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.esw.postureanalyzer.vision.DelegateType;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Firebase performance data uploader
 * Tracks and uploads performance metrics to Firebase Realtime Database
 */
public class PerformanceTracker {
    private static final String TAG = "PerformanceTracker";
    
    // Regional database URL for Asia Southeast
    private static final String DATABASE_URL = "https://postureanalyzer-b24a3-default-rtdb.asia-southeast1.firebasedatabase.app";
    
    private final Context context;
    private final DatabaseReference databaseRef;
    
    // Current session data
    private String sessionId;
    private DelegateType currentDelegate;
    private long sessionStartTime;
    private long modelLoadTime;
    private long warmupTime;
    
    // Performance data collection
    private final List<Long> inferenceTimes = new ArrayList<>();
    private final List<Double> fpsValues = new ArrayList<>();
    
    // Model information
    private String currentModelName;
    private long currentModelSize;
    
    public PerformanceTracker(Context context) {
        this.context = context;
        // Use regional database instance with correct reference
        FirebaseDatabase database = FirebaseDatabase.getInstance(DATABASE_URL);
        this.databaseRef = database.getReference("performance_data");
        this.sessionId = UUID.randomUUID().toString();
        this.sessionStartTime = System.currentTimeMillis();
    }
    
    /**
     * Start a new tracking session for a delegate
     */
    public void startSession(DelegateType delegate, long modelLoadTime, long warmupTime) {
        this.sessionId = UUID.randomUUID().toString();
        this.currentDelegate = delegate;
        this.sessionStartTime = System.currentTimeMillis();
        this.modelLoadTime = modelLoadTime;
        this.warmupTime = warmupTime;
        
        // Clear previous data
        inferenceTimes.clear();
        fpsValues.clear();
        
        Log.d(TAG, "Started new session for " + delegate.getDisplayName() + " (ID: " + sessionId + ")");
    }
    
    /**
     * Record a single inference time
     */
    public void recordInference(long inferenceTimeMicros) {
        inferenceTimes.add(inferenceTimeMicros);
    }
    
    /**
     * Record FPS measurement
     */
    public void recordFps(double fps) {
        fpsValues.add(fps);
    }
    
    /**
     * Set current model information
     */
    public void setModelInfo(String modelName, long modelSizeBytes) {
        this.currentModelName = modelName;
        this.currentModelSize = modelSizeBytes;
    }
    
    /**
     * Upload current session data to Firebase
     */
    public void uploadSession() {
        if (inferenceTimes.isEmpty()) {
            Log.w(TAG, "No inference data to upload");
            return;
        }
        
        // Check if delegate is set
        if (currentDelegate == null) {
            Log.w(TAG, "Delegate not set yet, skipping upload");
            return;
        }
        
        // Calculate aggregated statistics from ALL collected samples
        List<Long> sortedTimes = new ArrayList<>(inferenceTimes);
        Collections.sort(sortedTimes);
        
        long minInferenceTime = sortedTimes.get(0);
        long maxInferenceTime = sortedTimes.get(sortedTimes.size() - 1);
        long avgInferenceTime = calculateAverage(inferenceTimes);
        long p95InferenceTime = calculatePercentile(sortedTimes, 95);
        double avgFps = fpsValues.isEmpty() ? 0 : calculateAverageFps(fpsValues);
        
        // Create aggregated metrics upload
        PerformanceMetrics metrics = new PerformanceMetrics();
        
        // Device Information
        metrics.deviceId = getDeviceId();
        metrics.deviceName = Build.MODEL;
        metrics.deviceManufacturer = Build.MANUFACTURER;
        metrics.deviceModel = Build.DEVICE;
        metrics.androidVersion = Build.VERSION.RELEASE;
        metrics.buildNumber = Build.DISPLAY;
        
        // Processor Information
        metrics.processor = currentDelegate.getDisplayName();
        metrics.processorDetails = getProcessorDetails();
        
        // Use calculated statistics from all samples (matches "Show Stats" display)
        metrics.minInferenceTime = minInferenceTime;
        metrics.maxInferenceTime = maxInferenceTime;
        metrics.avgInferenceTime = avgInferenceTime;
        metrics.p95InferenceTime = p95InferenceTime;
        
        // Throughput
        metrics.avgFps = avgFps;
        metrics.totalInferences = inferenceTimes.size();
        
        // Model Info
        metrics.modelLoadTime = modelLoadTime;
        metrics.warmupTime = warmupTime;
        metrics.modelName = currentModelName != null ? currentModelName : "posture_models";
        metrics.modelSizeBytes = currentModelSize;
        
        // Timestamp
        metrics.timestamp = System.currentTimeMillis();
        metrics.sessionId = sessionId;
        
        // Upload to Firebase
        uploadToFirebase(metrics);
    }
    
    /**
     * Upload metrics to Firebase
     */
    private void uploadToFirebase(PerformanceMetrics metrics) {
        // Simplified structure for easier Firebase rules: performance_data/{processor}/{deviceModel}/{timestamp}
        String timestamp = String.valueOf(System.currentTimeMillis());
        String deviceModel = metrics.deviceModel.replaceAll("[^a-zA-Z0-9]", "_"); // Sanitize
        String processor = metrics.processor.replace("/", "_"); // Sanitize path
        
        String path = String.format("performance_data/%s/%s/%s", 
            processor,
            deviceModel,
            timestamp);
        
        databaseRef.child(path).setValue(metrics.toMap())
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "✓ Performance data uploaded successfully");
                Log.d(TAG, "  → Device: " + metrics.deviceName);
                Log.d(TAG, "  → Processor: " + metrics.processor);
                Log.d(TAG, "  → Avg Latency: " + metrics.avgInferenceTime + " μs");
                Log.d(TAG, "  → Samples: " + metrics.totalInferences);
                Log.d(TAG, "  → Path: " + path);
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "✗ Failed to upload performance data", e);
                Log.e(TAG, "  → Error: " + e.getMessage());
            });
    }
    
    /**
     * Get device unique ID
     */
    private String getDeviceId() {
        String androidId = Settings.Secure.getString(
            context.getContentResolver(), 
            Settings.Secure.ANDROID_ID);
        return androidId != null ? androidId : "unknown";
    }
    
    /**
     * Get processor details based on delegate type
     */
    private String getProcessorDetails() {
        switch (currentDelegate) {
            case GPU:
                return getGpuInfo();
            case NNAPI:
                return getNpuInfo();
            case CPU:
            default:
                return getCpuInfo();
        }
    }
    
    private String getCpuInfo() {
        return Build.HARDWARE + " - " + Build.SUPPORTED_ABIS[0];
    }
    
    private String getGpuInfo() {
        // GPU info would typically come from OpenGL ES
        return "Adreno 750 - OpenGL ES 3.2"; // Can be dynamically detected
    }
    
    private String getNpuInfo() {
        return "Hexagon DSP/NPU - NNAPI";
    }
    
    /**
     * Calculate average of long values
     */
    private long calculateAverage(List<Long> values) {
        long sum = 0;
        for (long value : values) {
            sum += value;
        }
        return sum / values.size();
    }
    
    /**
     * Calculate average FPS
     */
    private double calculateAverageFps(List<Double> values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }
    
    /**
     * Calculate percentile
     */
    private long calculatePercentile(List<Long> sortedValues, int percentile) {
        int index = (int) Math.ceil((percentile / 100.0) * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(index, sortedValues.size() - 1));
        return sortedValues.get(index);
    }
    
    /**
     * Get current session statistics for display
     */
    public String getSessionStats() {
        if (inferenceTimes.isEmpty()) {
            return "No data collected yet";
        }
        
        Collections.sort(inferenceTimes);
        long min = inferenceTimes.get(0);
        long max = inferenceTimes.get(inferenceTimes.size() - 1);
        long avg = calculateAverage(inferenceTimes);
        long p95 = calculatePercentile(inferenceTimes, 95);
        
        return String.format("Samples: %d\nMin: %d μs\nMax: %d μs\nAvg: %d μs\nP95: %d μs",
            inferenceTimes.size(), min, max, avg, p95);
    }
}
