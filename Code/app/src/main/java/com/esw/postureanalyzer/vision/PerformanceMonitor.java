package com.esw.postureanalyzer.vision;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Monitors and tracks performance metrics for ML inference
 * Tracks inference time, total processing time, and calculates statistics
 */
public class PerformanceMonitor {
    private static final String TAG = "PerformanceMonitor";
    private static final int WINDOW_SIZE = 30; // Rolling average over 30 frames

    private final List<Long> inferenceTimesMs = new ArrayList<>();
    private final List<Long> totalTimesMs = new ArrayList<>();
    private final String componentName;

    private long startTime;
    private long inferenceStartTime;

    public PerformanceMonitor(String componentName) {
        this.componentName = componentName;
    }

    /**
     * Mark the start of total processing (including pre/post processing)
     */
    public void startTotal() {
        startTime = System.nanoTime();
    }

    /**
     * Mark the start of inference only
     */
    public void startInference() {
        inferenceStartTime = System.nanoTime();
    }

    /**
     * Mark the end of inference
     */
    public void endInference() {
        long endTime = System.nanoTime();
        long durationUs = (endTime - inferenceStartTime) / 1_000; // Convert to microseconds
        
        inferenceTimesMs.add(durationUs);
        if (inferenceTimesMs.size() > WINDOW_SIZE) {
            inferenceTimesMs.remove(0);
        }
    }

    /**
     * Mark the end of total processing
     */
    public void endTotal() {
        long endTime = System.nanoTime();
        long durationUs = (endTime - startTime) / 1_000; // Convert to microseconds
        
        totalTimesMs.add(durationUs);
        if (totalTimesMs.size() > WINDOW_SIZE) {
            totalTimesMs.remove(0);
        }
    }

    /**
     * Get comprehensive statistics string
     */
    public String getStats() {
        if (inferenceTimesMs.isEmpty() || totalTimesMs.isEmpty()) {
            Log.w(TAG, componentName + ": No data collected yet (lists empty)");
            return componentName + ": No data yet";
        }

        long avgInference = calculateAverage(inferenceTimesMs);
        long minInference = calculateMin(inferenceTimesMs);
        long maxInference = calculateMax(inferenceTimesMs);
        
        long avgTotal = calculateAverage(totalTimesMs);
        long minTotal = calculateMin(totalTimesMs);
        long maxTotal = calculateMax(totalTimesMs);

        String stats = String.format(Locale.US,
            "%s\n  Inference: avg=%dμs min=%dμs max=%dμs\n  Total: avg=%dμs min=%dμs max=%dμs\n  FPS: %.1f",
            componentName,
            avgInference, minInference, maxInference,
            avgTotal, minTotal, maxTotal,
            avgTotal > 0 ? 1_000_000.0 / avgTotal : 0
        );
        
        Log.d(TAG, "Stats for " + componentName + ": samples=" + inferenceTimesMs.size() + ", avgInf=" + avgInference + "μs");
        return stats;
    }

    /**
     * Get average inference time in milliseconds
     */
    public long getAverageInferenceMs() {
        return inferenceTimesMs.isEmpty() ? 0 : calculateAverage(inferenceTimesMs);
    }

    /**
     * Get average total time in milliseconds
     */
    public long getAverageTotalMs() {
        return totalTimesMs.isEmpty() ? 0 : calculateAverage(totalTimesMs);
    }

    /**
     * Get the most recent inference time
     */
    public long getLastInferenceMs() {
        return inferenceTimesMs.isEmpty() ? 0 : inferenceTimesMs.get(inferenceTimesMs.size() - 1);
    }

    /**
     * Get the most recent total time
     */
    public long getLastTotalMs() {
        return totalTimesMs.isEmpty() ? 0 : totalTimesMs.get(totalTimesMs.size() - 1);
    }

    private long calculateAverage(List<Long> times) {
        long sum = 0;
        for (long time : times) {
            sum += time;
        }
        return sum / times.size();
    }

    private long calculateMin(List<Long> times) {
        long min = Long.MAX_VALUE;
        for (long time : times) {
            if (time < min) min = time;
        }
        return min;
    }

    private long calculateMax(List<Long> times) {
        long max = Long.MIN_VALUE;
        for (long time : times) {
            if (time > max) max = time;
        }
        return max;
    }

    /**
     * Reset all collected statistics
     */
    public void reset() {
        inferenceTimesMs.clear();
        totalTimesMs.clear();
    }

    /**
     * Log current statistics to logcat
     */
    public void logStats() {
        Log.d(TAG, getStats());
    }

    /**
     * Check if we have collected any data
     */
    public boolean hasData() {
        return !inferenceTimesMs.isEmpty() && !totalTimesMs.isEmpty();
    }
    
    /**
     * Get detailed metrics as a map for Firebase upload
     */
    public java.util.Map<String, Object> getMetricsMap() {
        java.util.Map<String, Object> metrics = new java.util.HashMap<>();
        
        if (inferenceTimesMs.isEmpty() || totalTimesMs.isEmpty()) {
            metrics.put("hasData", false);
            return metrics;
        }
        
        metrics.put("hasData", true);
        metrics.put("samples", inferenceTimesMs.size());
        metrics.put("avgInferenceUs", calculateAverage(inferenceTimesMs));
        metrics.put("minInferenceUs", calculateMin(inferenceTimesMs));
        metrics.put("maxInferenceUs", calculateMax(inferenceTimesMs));
        metrics.put("avgTotalUs", calculateAverage(totalTimesMs));
        metrics.put("minTotalUs", calculateMin(totalTimesMs));
        metrics.put("maxTotalUs", calculateMax(totalTimesMs));
        
        long avgTotal = calculateAverage(totalTimesMs);
        metrics.put("avgFps", avgTotal > 0 ? 1_000_000.0 / avgTotal : 0);
        
        return metrics;
    }
}
