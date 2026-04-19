package com.esw.postureanalyzer.vision;

import android.util.Log;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FirebaseManager {
    private static final String TAG = "FirebaseManager";
    private final DatabaseReference database;
    private final boolean storeLandmarks; // Toggle for storing landmark data
    
    // Regional database URL for Asia Southeast
    private static final String DATABASE_URL = "https://postureanalyzer-b24a3-default-rtdb.asia-southeast1.firebasedatabase.app";
    
    // Throttle logging to avoid excessive writes
    private long lastLogTime = 0;
    private static final long LOG_INTERVAL_MS = 2000; // Log every 2 seconds

    public FirebaseManager() {
        this(false); // Default: don't store landmarks to save bandwidth
    }

    public FirebaseManager(boolean storeLandmarks) {
        // Use the regional database instance
        FirebaseDatabase firebaseDatabase = FirebaseDatabase.getInstance(DATABASE_URL);
        database = firebaseDatabase.getReference("posture_logs");
        this.storeLandmarks = storeLandmarks;
    }

    /**
     * Log posture data to Firebase Realtime Database
     */
    public void logData(PostureClassifier.ClassificationResult result, List<NormalizedLandmark> landmarks) {
        // Throttle to avoid excessive writes
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime < LOG_INTERVAL_MS) {
            return;
        }
        lastLogTime = currentTime;

        if (result == null) {
            return;
        }

        String logId = database.push().getKey();
        if (logId == null) {
            return;
        }

        // Create structured log entry
        Map<String, Object> logEntry = new HashMap<>();
        
        // Timestamp information
        logEntry.put("timestamp", currentTime);
        logEntry.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(currentTime)));
        
        // Posture classification results
        Map<String, String> postureData = new HashMap<>();
        postureData.put("slouch", result.getSlouchStatus());
        postureData.put("legs", result.getLegsStatus());
        postureData.put("lean", result.getLeanStatus());
        logEntry.put("posture", postureData);
        
        // Calculate and store evaluation metrics
        if (landmarks != null && !landmarks.isEmpty()) {
            Map<String, Object> metrics = new HashMap<>();
            double pdj = EvaluationMetrics.calculatePDJ(landmarks, 0.5);
            double oks = EvaluationMetrics.calculateOKS(landmarks);
            metrics.put("pdj", Math.round(pdj * 100.0) / 100.0);
            metrics.put("oks", Math.round(oks * 100.0) / 100.0);
            logEntry.put("metrics", metrics);
            
            // Optionally store landmark data (can be bandwidth-intensive)
            if (storeLandmarks) {
                List<Map<String, Float>> landmarksList = new ArrayList<>();
                for (NormalizedLandmark lm : landmarks) {
                    Map<String, Float> landmarkData = new HashMap<>();
                    landmarkData.put("x", lm.x());
                    landmarkData.put("y", lm.y());
                    landmarkData.put("z", lm.z());
                    landmarkData.put("visibility", lm.visibility().orElse(0.0f));
                    landmarksList.add(landmarkData);
                }
                logEntry.put("landmarks", landmarksList);
            }
        }

        // Write to Firebase
        database.child(logId).setValue(logEntry);
    }

    /**
     * Log posture data with additional metrics information
     */
    public void logDataWithMetrics(PostureClassifier.ClassificationResult result, 
                                    List<NormalizedLandmark> landmarks,
                                    String metricsString,
                                    long inferenceTime) {
        // Throttle to avoid excessive writes
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime < LOG_INTERVAL_MS) {
            return;
        }
        lastLogTime = currentTime;

        if (result == null) {
            return;
        }

        String logId = database.push().getKey();
        if (logId == null) {
            return;
        }

        // Create structured log entry
        Map<String, Object> logEntry = new HashMap<>();
        
        // Timestamp information
        logEntry.put("timestamp", currentTime);
        logEntry.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(currentTime)));
        
        // Posture classification results
        Map<String, String> postureData = new HashMap<>();
        postureData.put("slouch", result.getSlouchStatus());
        postureData.put("legs", result.getLegsStatus());
        postureData.put("lean", result.getLeanStatus());
        logEntry.put("posture", postureData);
        
        // Performance metrics
        logEntry.put("inferenceTimeMs", inferenceTime);
        logEntry.put("metricsString", metricsString);
        
        // Calculate and store evaluation metrics
        if (landmarks != null && !landmarks.isEmpty()) {
            Map<String, Object> metrics = new HashMap<>();
            double pdj = EvaluationMetrics.calculatePDJ(landmarks, 0.5);
            double oks = EvaluationMetrics.calculateOKS(landmarks);
            metrics.put("pdj", Math.round(pdj * 100.0) / 100.0);
            metrics.put("oks", Math.round(oks * 100.0) / 100.0);
            logEntry.put("metrics", metrics);
            
            // Optionally store landmark data
            if (storeLandmarks) {
                List<Map<String, Float>> landmarksList = new ArrayList<>();
                for (NormalizedLandmark lm : landmarks) {
                    Map<String, Float> landmarkData = new HashMap<>();
                    landmarkData.put("x", lm.x());
                    landmarkData.put("y", lm.y());
                    landmarkData.put("z", lm.z());
                    landmarkData.put("visibility", lm.visibility().orElse(0.0f));
                    landmarksList.add(landmarkData);
                }
                logEntry.put("landmarks", landmarksList);
            }
        }

        // Write to Firebase
        database.child(logId).setValue(logEntry);
    }

    /**
     * Update the log interval for throttling
     */
    public void setLogInterval(long intervalMs) {
        // This would require making LOG_INTERVAL_MS non-final
        // For now, it's fixed at 2 seconds
    }

    /**
     * Test Firebase connection by writing and reading a test entry
     */
    public void testConnection(ConnectionTestCallback callback) {
        DatabaseReference testRef = database.child("_connection_test");
        Map<String, Object> testData = new HashMap<>();
        testData.put("timestamp", System.currentTimeMillis());
        testData.put("status", "testing");
        
        // Try to write
        testRef.setValue(testData)
            .addOnSuccessListener(aVoid -> {
                // Try to read back
                testRef.get().addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        // Clean up test data
                        testRef.removeValue();
                        callback.onConnectionSuccess("Firebase connected successfully!");
                    } else {
                        callback.onConnectionFailure("Read failed: " + task.getException());
                    }
                });
            })
            .addOnFailureListener(e -> {
                callback.onConnectionFailure("Write failed: " + e.getMessage());
            });
    }

    /**
     * Callback interface for connection testing
     */
    public interface ConnectionTestCallback {
        void onConnectionSuccess(String message);
        void onConnectionFailure(String error);
    }
}