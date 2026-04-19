package com.esw.postureanalyzer.vision;

import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for querying and analyzing posture data from Firebase.
 * Use this class to retrieve historical data for analysis and reporting.
 */
public class FirebaseDataRetriever {
    private static final String TAG = "FirebaseDataRetriever";
    private final DatabaseReference database;
    
    // Regional database URL for Asia Southeast
    private static final String DATABASE_URL = "https://postureanalyzer-b24a3-default-rtdb.asia-southeast1.firebasedatabase.app";

    public FirebaseDataRetriever() {
        // Use the regional database instance
        FirebaseDatabase firebaseDatabase = FirebaseDatabase.getInstance(DATABASE_URL);
        database = firebaseDatabase.getReference("posture_logs");
    }

    /**
     * Callback interface for receiving query results
     */
    public interface DataCallback {
        void onDataReceived(List<Map<String, Object>> data);
        void onError(String error);
    }

    /**
     * Get all posture logs within a time range
     *
     * @param startTime Start timestamp (milliseconds)
     * @param endTime End timestamp (milliseconds)
     * @param callback Callback to receive results
     */
    public void getDataByTimeRange(long startTime, long endTime, DataCallback callback) {
        Query query = database.orderByChild("timestamp")
                .startAt(startTime)
                .endAt(endTime);

        executeQuery(query, callback);
    }

    /**
     * Get the last N entries
     *
     * @param count Number of entries to retrieve
     * @param callback Callback to receive results
     */
    public void getRecentData(int count, DataCallback callback) {
        Query query = database.orderByChild("timestamp")
                .limitToLast(count);

        executeQuery(query, callback);
    }

    /**
     * Get entries where user was slouching
     *
     * @param callback Callback to receive results
     */
    public void getSlouchingData(DataCallback callback) {
        Query query = database.orderByChild("posture/slouch")
                .equalTo("Slouching");

        executeQuery(query, callback);
    }

    /**
     * Get entries where user was sitting cross-legged
     *
     * @param callback Callback to receive results
     */
    public void getCrossLeggedData(DataCallback callback) {
        Query query = database.orderByChild("posture/legs")
                .equalTo("Cross-legged");

        executeQuery(query, callback);
    }

    /**
     * Get entries where user was leaning (left or right)
     *
     * @param direction "Left" or "Right"
     * @param callback Callback to receive results
     */
    public void getLeaningData(String direction, DataCallback callback) {
        Query query = database.orderByChild("posture/lean")
                .equalTo(direction);

        executeQuery(query, callback);
    }

    /**
     * Calculate posture statistics for a given time period
     *
     * @param startTime Start timestamp (milliseconds)
     * @param endTime End timestamp (milliseconds)
     * @param callback Callback to receive statistics
     */
    public void getPostureStatistics(long startTime, long endTime, StatisticsCallback callback) {
        getDataByTimeRange(startTime, endTime, new DataCallback() {
            @Override
            public void onDataReceived(List<Map<String, Object>> data) {
                PostureStatistics stats = calculateStatistics(data);
                callback.onStatisticsCalculated(stats);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    /**
     * Callback interface for receiving statistics
     */
    public interface StatisticsCallback {
        void onStatisticsCalculated(PostureStatistics statistics);
        void onError(String error);
    }

    /**
     * Statistics class holding posture analysis results
     */
    public static class PostureStatistics {
        public int totalEntries;
        public int slouchingCount;
        public int goodPostureCount;
        public int crossLeggedCount;
        public int normalLegsCount;
        public int leanLeftCount;
        public int leanRightCount;
        public int uprightCount;
        public double avgPdj;
        public double avgOks;
        public double avgInferenceTime;

        public double getSlouchingPercentage() {
            return totalEntries > 0 ? (slouchingCount * 100.0 / totalEntries) : 0.0;
        }

        public double getGoodPosturePercentage() {
            return totalEntries > 0 ? (goodPostureCount * 100.0 / totalEntries) : 0.0;
        }

        public double getCrossLeggedPercentage() {
            return totalEntries > 0 ? (crossLeggedCount * 100.0 / totalEntries) : 0.0;
        }

        @Override
        public String toString() {
            return String.format(
                "PostureStatistics:\n" +
                "  Total Entries: %d\n" +
                "  Slouching: %d (%.1f%%)\n" +
                "  Good Posture: %d (%.1f%%)\n" +
                "  Cross-legged: %d (%.1f%%)\n" +
                "  Avg PDJ: %.1f\n" +
                "  Avg OKS: %.1f\n" +
                "  Avg Inference Time: %.1fms",
                totalEntries,
                slouchingCount, getSlouchingPercentage(),
                goodPostureCount, getGoodPosturePercentage(),
                crossLeggedCount, getCrossLeggedPercentage(),
                avgPdj, avgOks, avgInferenceTime
            );
        }
    }

    /**
     * Calculate statistics from a list of data entries
     */
    private PostureStatistics calculateStatistics(List<Map<String, Object>> data) {
        PostureStatistics stats = new PostureStatistics();
        
        try {
            if (data == null || data.isEmpty()) {
                Log.d(TAG, "No data to calculate statistics");
                return stats;
            }
            
            stats.totalEntries = data.size();

            double totalPdj = 0;
            double totalOks = 0;
            double totalInferenceTime = 0;
            int pdjCount = 0;
            int oksCount = 0;
            int inferenceCount = 0;

            for (Map<String, Object> entry : data) {
                try {
                    // Posture analysis
                    @SuppressWarnings("unchecked")
                    Map<String, String> posture = (Map<String, String>) entry.get("posture");
                    if (posture != null) {
                        String slouch = posture.get("slouch");
                        if ("Slouching".equals(slouch) || "yes".equals(slouch)) stats.slouchingCount++;
                        if ("Good Posture".equals(slouch) || "no".equals(slouch)) stats.goodPostureCount++;

                        String legs = posture.get("legs");
                        if ("Cross-legged".equals(legs) || "yes".equals(legs)) stats.crossLeggedCount++;
                        if ("Normal".equals(legs) || "no".equals(legs)) stats.normalLegsCount++;

                        String lean = posture.get("lean");
                        if ("Left".equals(lean) || "left".equals(lean)) stats.leanLeftCount++;
                        if ("Right".equals(lean) || "right".equals(lean)) stats.leanRightCount++;
                        if ("Upright".equals(lean) || "upright".equals(lean)) stats.uprightCount++;
                    }

                    // Metrics analysis
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metrics = (Map<String, Object>) entry.get("metrics");
                    if (metrics != null) {
                        if (metrics.containsKey("pdj")) {
                            totalPdj += ((Number) metrics.get("pdj")).doubleValue();
                            pdjCount++;
                        }
                        if (metrics.containsKey("oks")) {
                            totalOks += ((Number) metrics.get("oks")).doubleValue();
                            oksCount++;
                        }
                    }

                    // Performance analysis
                    if (entry.containsKey("inferenceTimeMs")) {
                        totalInferenceTime += ((Number) entry.get("inferenceTimeMs")).doubleValue();
                        inferenceCount++;
                    } else if (entry.containsKey("inferenceTime")) {
                        totalInferenceTime += ((Number) entry.get("inferenceTime")).doubleValue();
                        inferenceCount++;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing entry in statistics", e);
                    // Continue processing other entries
                }
            }

            stats.avgPdj = pdjCount > 0 ? totalPdj / pdjCount : 0;
            stats.avgOks = oksCount > 0 ? totalOks / oksCount : 0;
            stats.avgInferenceTime = inferenceCount > 0 ? totalInferenceTime / inferenceCount : 0;

            Log.d(TAG, "Statistics calculated: " + stats.totalEntries + " entries");
        } catch (Exception e) {
            Log.e(TAG, "Error calculating statistics", e);
        }

        return stats;
    }

    /**
     * Helper method to execute a query and return results
     */
    private void executeQuery(Query query, DataCallback callback) {
        if (query == null || callback == null) {
            Log.e(TAG, "Query or callback is null");
            if (callback != null) {
                callback.onError("Invalid query parameters");
            }
            return;
        }
        
        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                try {
                    List<Map<String, Object>> results = new ArrayList<>();
                    
                    if (!snapshot.exists()) {
                        Log.d(TAG, "No data found for query");
                        callback.onDataReceived(results);
                        return;
                    }
                    
                    for (DataSnapshot child : snapshot.getChildren()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> data = (Map<String, Object>) child.getValue();
                            if (data != null) {
                                data.put("key", child.getKey());
                                results.add(data);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing child data: " + child.getKey(), e);
                            // Continue processing other entries
                        }
                    }
                    
                    Log.d(TAG, "Query returned " + results.size() + " entries");
                    callback.onDataReceived(results);
                } catch (Exception e) {
                    Log.e(TAG, "Error in onDataChange", e);
                    callback.onError("Error processing data: " + e.getMessage());
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                try {
                    String errorMsg = "Database error: " + error.getMessage();
                    Log.e(TAG, errorMsg);
                    callback.onError(errorMsg);
                } catch (Exception e) {
                    Log.e(TAG, "Error in onCancelled", e);
                }
            }
        });
    }

    /**
     * Delete all entries older than a specified time
     *
     * @param olderThan Timestamp (milliseconds) - delete entries before this time
     */
    public void deleteOldData(long olderThan, DeleteCallback callback) {
        Query query = database.orderByChild("timestamp").endAt(olderThan);

        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                int deleteCount = 0;
                for (DataSnapshot child : snapshot.getChildren()) {
                    child.getRef().removeValue();
                    deleteCount++;
                }
                callback.onDeleteComplete(deleteCount);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                callback.onError("Failed to delete: " + error.getMessage());
            }
        });
    }

    public interface DeleteCallback {
        void onDeleteComplete(int count);
        void onError(String error);
    }

    /**
     * Callback interface for heatmap data
     */
    public interface HeatmapDataCallback {
        void onHeatmapDataReady(List<Map<String, Object>> dailyData, List<Map<String, Object>> hourlyData);
        void onError(String error);
    }

    /**
     * Get data formatted for heatmap visualization
     * Returns both daily and hourly aggregated data
     */
    public void getHeatmapData(long startTime, long endTime, HeatmapDataCallback callback) {
        Query query = database.orderByChild("timestamp")
                .startAt(startTime)
                .endAt(endTime);

        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                try {
                    List<Map<String, Object>> dailyData = new ArrayList<>();
                    List<Map<String, Object>> hourlyData = new ArrayList<>();
                    
                    if (!snapshot.exists()) {
                        callback.onHeatmapDataReady(dailyData, hourlyData);
                        return;
                    }

                    // Aggregate data by day and hour
                    Map<String, DayStats> dayStatsMap = new HashMap<>();
                    Map<Integer, HourStats> hourStatsMap = new HashMap<>();

                    for (DataSnapshot child : snapshot.getChildren()) {
                        try {
                            Long timestamp = child.child("timestamp").getValue(Long.class);
                            if (timestamp == null) continue;

                            java.util.Calendar cal = java.util.Calendar.getInstance();
                            cal.setTimeInMillis(timestamp);
                            
                            String dateKey = String.format("%04d-%02d-%02d", 
                                cal.get(java.util.Calendar.YEAR),
                                cal.get(java.util.Calendar.MONTH) + 1,
                                cal.get(java.util.Calendar.DAY_OF_MONTH));
                            int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);

                            // Get posture data
                            DataSnapshot postureSnap = child.child("posture");
                            String slouch = postureSnap.child("slouch").getValue(String.class);
                            boolean isGoodPosture = "Good Posture".equals(slouch);

                            // Aggregate by day
                            DayStats dayStats = dayStatsMap.get(dateKey);
                            if (dayStats == null) {
                                dayStats = new DayStats(dateKey);
                                dayStatsMap.put(dateKey, dayStats);
                            }
                            dayStats.totalCount++;
                            if (isGoodPosture) dayStats.goodCount++;

                            // Aggregate by hour
                            HourStats hourStats = hourStatsMap.get(hour);
                            if (hourStats == null) {
                                hourStats = new HourStats(hour);
                                hourStatsMap.put(hour, hourStats);
                            }
                            hourStats.totalCount++;
                            if (isGoodPosture) hourStats.goodCount++;

                        } catch (Exception e) {
                            Log.e(TAG, "Error processing entry", e);
                        }
                    }

                    // Convert to list format
                    for (DayStats stats : dayStatsMap.values()) {
                        Map<String, Object> dayData = new HashMap<>();
                        dayData.put("date", stats.date);
                        dayData.put("score", stats.getScore());
                        dayData.put("totalSessions", stats.totalCount);
                        dailyData.add(dayData);
                    }

                    for (HourStats stats : hourStatsMap.values()) {
                        Map<String, Object> hourData = new HashMap<>();
                        hourData.put("hour", stats.hour);
                        hourData.put("score", stats.getScore());
                        hourData.put("sessionCount", stats.totalCount);
                        hourlyData.add(hourData);
                    }

                    callback.onHeatmapDataReady(dailyData, hourlyData);

                } catch (Exception e) {
                    Log.e(TAG, "Error processing heatmap data", e);
                    callback.onError("Error: " + e.getMessage());
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                callback.onError("Database error: " + error.getMessage());
            }
        });
    }

    /**
     * Get body position heatmap data
     * Analyzes which body parts have the most issues
     */
    public void getBodyHeatmapData(long startTime, long endTime, BodyHeatmapCallback callback) {
        Query query = database.orderByChild("timestamp")
                .startAt(startTime)
                .endAt(endTime);

        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                try {
                    BodyHeatStats stats = new BodyHeatStats();
                    
                    if (!snapshot.exists()) {
                        callback.onBodyHeatmapReady(stats);
                        return;
                    }

                    for (DataSnapshot child : snapshot.getChildren()) {
                        try {
                            DataSnapshot postureSnap = child.child("posture");
                            String slouch = postureSnap.child("slouch").getValue(String.class);
                            String lean = postureSnap.child("lean").getValue(String.class);
                            String legs = postureSnap.child("legs").getValue(String.class);

                            stats.totalCount++;

                            // Count issues
                            if ("Slouching".equals(slouch)) {
                                stats.headIssues++;
                                stats.neckIssues++;
                                stats.backUpperIssues++;
                            }

                            if ("Left".equals(lean)) {
                                stats.shoulderLeftIssues++;
                                stats.backUpperIssues++;
                            } else if ("Right".equals(lean)) {
                                stats.shoulderRightIssues++;
                                stats.backUpperIssues++;
                            }

                            if ("Cross-legged".equals(legs)) {
                                stats.hipIssues++;
                                stats.backLowerIssues++;
                            }

                        } catch (Exception e) {
                            Log.e(TAG, "Error processing entry", e);
                        }
                    }

                    callback.onBodyHeatmapReady(stats);

                } catch (Exception e) {
                    Log.e(TAG, "Error processing body heatmap", e);
                    callback.onError("Error: " + e.getMessage());
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                callback.onError("Database error: " + error.getMessage());
            }
        });
    }

    public interface BodyHeatmapCallback {
        void onBodyHeatmapReady(BodyHeatStats stats);
        void onError(String error);
    }

    // Helper classes for data aggregation
    private static class DayStats {
        String date;
        int totalCount = 0;
        int goodCount = 0;

        DayStats(String date) {
            this.date = date;
        }

        float getScore() {
            return totalCount > 0 ? (goodCount * 100f / totalCount) : 0;
        }
    }

    private static class HourStats {
        int hour;
        int totalCount = 0;
        int goodCount = 0;

        HourStats(int hour) {
            this.hour = hour;
        }

        float getScore() {
            return totalCount > 0 ? (goodCount * 100f / totalCount) : 0;
        }
    }

    public static class BodyHeatStats {
        public int totalCount = 0;
        public int headIssues = 0;
        public int neckIssues = 0;
        public int shoulderLeftIssues = 0;
        public int shoulderRightIssues = 0;
        public int backUpperIssues = 0;
        public int backLowerIssues = 0;
        public int hipIssues = 0;

        public float getHeadHeat() {
            return totalCount > 0 ? (headIssues * 100f / totalCount) : 0;
        }

        public float getNeckHeat() {
            return totalCount > 0 ? (neckIssues * 100f / totalCount) : 0;
        }

        public float getShoulderLeftHeat() {
            return totalCount > 0 ? (shoulderLeftIssues * 100f / totalCount) : 0;
        }

        public float getShoulderRightHeat() {
            return totalCount > 0 ? (shoulderRightIssues * 100f / totalCount) : 0;
        }

        public float getBackUpperHeat() {
            return totalCount > 0 ? (backUpperIssues * 100f / totalCount) : 0;
        }

        public float getBackLowerHeat() {
            return totalCount > 0 ? (backLowerIssues * 100f / totalCount) : 0;
        }

        public float getHipHeat() {
            return totalCount > 0 ? (hipIssues * 100f / totalCount) : 0;
        }
    }
}
