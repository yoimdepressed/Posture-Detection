package com.esw.postureanalyzer.performance;

import java.util.HashMap;
import java.util.Map;

/**
 * Performance metrics data class for Firebase upload
 */
public class PerformanceMetrics {
    // Device Information
    public String deviceId;
    public String deviceName;
    public String deviceManufacturer;
    public String deviceModel;
    public String androidVersion;
    public String buildNumber;
    
    // Processor Information
    public String processor; // "CPU", "GPU", "NPU/NNAPI"
    public String processorDetails; // Hardware info
    
    // Performance Metrics
    // Inference Time (Latency)
    public long minInferenceTime; // microseconds
    public long maxInferenceTime; // microseconds
    public long avgInferenceTime; // microseconds
    public long p95InferenceTime; // 95th percentile microseconds
    
    // Throughput
    public double avgFps;
    public int totalInferences;
    
    // Model Load Time
    public long modelLoadTime; // milliseconds
    public long warmupTime; // milliseconds (for NNAPI)
    
    // Model Information
    public String modelName;
    public long modelSizeBytes;
    
    // Memory Usage (optional - can be added later)
    public long peakMemoryMb;
    
    // Power Consumption (optional - can be added later)
    public double avgPowerMw;
    
    // Timestamp
    public long timestamp;
    public String sessionId;
    
    public PerformanceMetrics() {
        // Default constructor for Firebase
    }
    
    /**
     * Convert to Map for Firebase upload
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        
        // Device Info
        map.put("deviceId", deviceId);
        map.put("deviceName", deviceName);
        map.put("deviceManufacturer", deviceManufacturer);
        map.put("deviceModel", deviceModel);
        map.put("androidVersion", androidVersion);
        map.put("buildNumber", buildNumber);
        
        // Processor Info
        map.put("processor", processor);
        map.put("processorDetails", processorDetails);
        
        // Performance Metrics
        map.put("minInferenceTime", minInferenceTime);
        map.put("maxInferenceTime", maxInferenceTime);
        map.put("avgInferenceTime", avgInferenceTime);
        map.put("p95InferenceTime", p95InferenceTime);
        map.put("avgFps", avgFps);
        map.put("totalInferences", totalInferences);
        
        // Model Info
        map.put("modelLoadTime", modelLoadTime);
        map.put("warmupTime", warmupTime);
        map.put("modelName", modelName);
        map.put("modelSizeBytes", modelSizeBytes);
        
        // Optional metrics
        if (peakMemoryMb > 0) map.put("peakMemoryMb", peakMemoryMb);
        if (avgPowerMw > 0) map.put("avgPowerMw", avgPowerMw);
        
        // Timestamp
        map.put("timestamp", timestamp);
        map.put("sessionId", sessionId);
        
        return map;
    }
}
