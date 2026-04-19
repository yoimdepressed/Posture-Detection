package com.esw.postureanalyzer;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.esw.postureanalyzer.vision.FirebaseDataRetriever;
import com.esw.postureanalyzer.views.PostureHeatmapView;
import com.esw.postureanalyzer.views.HourlyPostureHeatmapView;
import com.esw.postureanalyzer.views.BodyPositionHeatmapView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class DashboardActivity extends AppCompatActivity {
    private static final String TAG = "DashboardActivity";
    
    private TextView totalSessionsText;
    private TextView goodPosturePercentText;
    private TextView slouchingPercentText;
    private TextView crossleggedPercentText;
    private TextView leanLeftCountText;
    private TextView leanRightCountText;
    private TextView uprightCountText;
    private TextView avgPdjText;
    private TextView avgOksText;
    private TextView avgInferenceTimeText;
    private ProgressBar loadingProgress;
    private TextView emptyState;
    private RadioGroup timePeriodGroup;
    
    // Heatmap views
    private PostureHeatmapView postureHeatmapView;
    private HourlyPostureHeatmapView hourlyHeatmapView;
    private BodyPositionHeatmapView bodyHeatmapView;
    
    private FirebaseDataRetriever dataRetriever;
    private long startTime;
    private long endTime;
    private boolean isActivityRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_dashboard);
            isActivityRunning = true;
            
            Log.d(TAG, "DashboardActivity onCreate");
            
            initializeViews();
            
            try {
                dataRetriever = new FirebaseDataRetriever();
            } catch (Exception e) {
                Log.e(TAG, "Error initializing Firebase retriever", e);
                Toast.makeText(this, "Firebase initialization failed", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            
            // Default to today
            setTimePeriod(TimePeriod.TODAY);
            loadDashboardData();
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
            Toast.makeText(this, "Error loading dashboard: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isActivityRunning = false;
        Log.d(TAG, "DashboardActivity onDestroy");
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "DashboardActivity onPause");
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "DashboardActivity onResume");
    }

    private void initializeViews() {
        try {
            // Header buttons
            ImageView backButton = findViewById(R.id.back_button);
            ImageView refreshButton = findViewById(R.id.refresh_button);
            ImageView emailSettingsButton = findViewById(R.id.email_settings_button);
            
            if (backButton != null) {
                backButton.setOnClickListener(v -> finish());
            }
            if (refreshButton != null) {
                refreshButton.setOnClickListener(v -> loadDashboardData());
            }
            if (emailSettingsButton != null) {
                emailSettingsButton.setOnClickListener(v -> {
                    Intent intent = new Intent(DashboardActivity.this, EmailSettingsActivity.class);
                    startActivity(intent);
                });
            }
            
            // Summary cards
            totalSessionsText = findViewById(R.id.total_sessions);
            goodPosturePercentText = findViewById(R.id.good_posture_percent);
            slouchingPercentText = findViewById(R.id.slouching_percent);
            crossleggedPercentText = findViewById(R.id.crosslegged_percent);
            
            // Detailed statistics
            leanLeftCountText = findViewById(R.id.lean_left_count);
            leanRightCountText = findViewById(R.id.lean_right_count);
            uprightCountText = findViewById(R.id.upright_count);
            avgPdjText = findViewById(R.id.avg_pdj);
            avgOksText = findViewById(R.id.avg_oks);
            avgInferenceTimeText = findViewById(R.id.avg_inference_time);
            
            // Loading and empty state
            loadingProgress = findViewById(R.id.loading_progress);
            emptyState = findViewById(R.id.empty_state);
            
            // Time period selector
            timePeriodGroup = findViewById(R.id.time_period_group);
            
            // Heatmap views
            postureHeatmapView = findViewById(R.id.posture_heatmap);
            hourlyHeatmapView = findViewById(R.id.hourly_heatmap);
            bodyHeatmapView = findViewById(R.id.body_heatmap);
            
            if (timePeriodGroup != null) {
                timePeriodGroup.setOnCheckedChangeListener((group, checkedId) -> {
                    if (!isActivityRunning || isFinishing()) {
                        return;
                    }
                    try {
                        if (checkedId == R.id.period_today) {
                            setTimePeriod(TimePeriod.TODAY);
                        } else if (checkedId == R.id.period_week) {
                            setTimePeriod(TimePeriod.WEEK);
                        } else if (checkedId == R.id.period_month) {
                            setTimePeriod(TimePeriod.MONTH);
                        }
                        loadDashboardData();
                    } catch (Exception e) {
                        Log.e(TAG, "Error handling time period change", e);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in initializeViews", e);
            throw e; // Re-throw to be caught in onCreate
        }
    }

    private void setTimePeriod(TimePeriod period) {
        Calendar calendar = Calendar.getInstance();
        endTime = calendar.getTimeInMillis();
        
        switch (period) {
            case TODAY:
                calendar.set(Calendar.HOUR_OF_DAY, 0);
                calendar.set(Calendar.MINUTE, 0);
                calendar.set(Calendar.SECOND, 0);
                calendar.set(Calendar.MILLISECOND, 0);
                startTime = calendar.getTimeInMillis();
                break;
            case WEEK:
                startTime = endTime - (7L * 24 * 60 * 60 * 1000);
                break;
            case MONTH:
                startTime = endTime - (30L * 24 * 60 * 60 * 1000);
                break;
        }
    }

    private void loadDashboardData() {
        if (!isActivityRunning || isFinishing()) {
            return;
        }
        
        showLoading(true);
        showEmptyState(false);
        
        try {
            // Load statistics
            dataRetriever.getPostureStatistics(startTime, endTime,
                new FirebaseDataRetriever.StatisticsCallback() {
                    @Override
                    public void onStatisticsCalculated(FirebaseDataRetriever.PostureStatistics stats) {
                        if (!isActivityRunning || isFinishing()) {
                            return;
                        }
                        
                        try {
                            runOnUiThread(() -> {
                                if (!isActivityRunning || isFinishing()) {
                                    return;
                                }
                                
                                try {
                                    showLoading(false);
                                    
                                    if (stats == null || stats.totalEntries == 0) {
                                        showEmptyState(true);
                                    } else {
                                        showEmptyState(false);
                                        updateUI(stats);
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Error updating UI", e);
                                    showError("Error displaying data");
                                }
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Error in onStatisticsCalculated", e);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (!isActivityRunning || isFinishing()) {
                            return;
                        }
                        
                        try {
                            runOnUiThread(() -> {
                                if (!isActivityRunning || isFinishing()) {
                                    return;
                                }
                                showLoading(false);
                                showError("Error loading data: " + error);
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Error handling error callback", e);
                        }
                    }
                }
            );
            
            // Load heatmap data
            loadHeatmapData();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in loadDashboardData", e);
            showLoading(false);
            showError("Failed to load data");
        }
    }

    /**
     * Load and display heatmap visualizations
     */
    private void loadHeatmapData() {
        if (!isActivityRunning || isFinishing()) {
            return;
        }

        try {
            // Load calendar and hourly heatmaps
            dataRetriever.getHeatmapData(startTime, endTime,
                new FirebaseDataRetriever.HeatmapDataCallback() {
                    @Override
                    public void onHeatmapDataReady(List<Map<String, Object>> dailyData, List<Map<String, Object>> hourlyData) {
                        if (!isActivityRunning || isFinishing()) {
                            return;
                        }

                        runOnUiThread(() -> {
                            try {
                                // Update calendar heatmap
                                if (postureHeatmapView != null && !dailyData.isEmpty()) {
                                    List<PostureHeatmapView.DayData> dayDataList = new ArrayList<>();
                                    
                                    // Sort by date
                                    Collections.sort(dailyData, (a, b) -> {
                                        String dateA = (String) a.get("date");
                                        String dateB = (String) b.get("date");
                                        return dateA.compareTo(dateB);
                                    });
                                    
                                    for (Map<String, Object> data : dailyData) {
                                        String date = (String) data.get("date");
                                        Number scoreNum = (Number) data.get("score");
                                        Number sessionsNum = (Number) data.get("totalSessions");
                                        
                                        float score = scoreNum != null ? scoreNum.floatValue() : 0;
                                        int sessions = sessionsNum != null ? sessionsNum.intValue() : 0;
                                        
                                        dayDataList.add(new PostureHeatmapView.DayData(0, score, sessions, date));
                                    }
                                    postureHeatmapView.setData(dayDataList);
                                }

                                // Update hourly heatmap
                                if (hourlyHeatmapView != null && !hourlyData.isEmpty()) {
                                    List<HourlyPostureHeatmapView.HourData> hourDataList = new ArrayList<>();
                                    
                                    // Ensure all 24 hours are represented
                                    int[] hourlyCounts = new int[24];
                                    float[] hourlyScores = new float[24];
                                    
                                    for (Map<String, Object> data : hourlyData) {
                                        Number hourNum = (Number) data.get("hour");
                                        Number scoreNum = (Number) data.get("score");
                                        Number countNum = (Number) data.get("sessionCount");
                                        
                                        if (hourNum != null) {
                                            int hour = hourNum.intValue();
                                            if (hour >= 0 && hour < 24) {
                                                hourlyScores[hour] = scoreNum != null ? scoreNum.floatValue() : 0;
                                                hourlyCounts[hour] = countNum != null ? countNum.intValue() : 0;
                                            }
                                        }
                                    }
                                    
                                    for (int i = 0; i < 24; i++) {
                                        hourDataList.add(new HourlyPostureHeatmapView.HourData(i, hourlyScores[i], hourlyCounts[i]));
                                    }
                                    hourlyHeatmapView.setHourlyData(hourDataList);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error updating heatmap views", e);
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Error loading heatmap data: " + error);
                    }
                }
            );

            // Load body position heatmap
            dataRetriever.getBodyHeatmapData(startTime, endTime,
                new FirebaseDataRetriever.BodyHeatmapCallback() {
                    @Override
                    public void onBodyHeatmapReady(FirebaseDataRetriever.BodyHeatStats stats) {
                        if (!isActivityRunning || isFinishing()) {
                            return;
                        }

                        runOnUiThread(() -> {
                            try {
                                if (bodyHeatmapView != null) {
                                    bodyHeatmapView.setBodyHeatData(
                                        stats.getHeadHeat(),
                                        stats.getNeckHeat(),
                                        stats.getShoulderLeftHeat(),
                                        stats.getShoulderRightHeat(),
                                        stats.getBackUpperHeat(),
                                        stats.getBackLowerHeat(),
                                        stats.getHipHeat()
                                    );
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error updating body heatmap", e);
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Error loading body heatmap: " + error);
                    }
                }
            );

        } catch (Exception e) {
            Log.e(TAG, "Error loading heatmap data", e);
        }
    }

    private void updateUI(FirebaseDataRetriever.PostureStatistics stats) {
        if (!isActivityRunning || isFinishing()) {
            return;
        }
        
        try {
            if (stats == null) {
                Log.e(TAG, "Stats object is null");
                showError("No data available");
                return;
            }
            
            // Summary cards with null safety checks for each TextView
            if (totalSessionsText != null) {
                totalSessionsText.setText(String.valueOf(stats.totalEntries));
            }
            if (goodPosturePercentText != null) {
                goodPosturePercentText.setText(String.format("%.0f%%", stats.getGoodPosturePercentage()));
            }
            if (slouchingPercentText != null) {
                slouchingPercentText.setText(String.format("%.0f%%", stats.getSlouchingPercentage()));
            }
            if (crossleggedPercentText != null) {
                crossleggedPercentText.setText(String.format("%.0f%%", stats.getCrossLeggedPercentage()));
            }
            
            // Detailed statistics with null safety
            if (leanLeftCountText != null) {
                leanLeftCountText.setText(String.valueOf(stats.leanLeftCount));
            }
            if (leanRightCountText != null) {
                leanRightCountText.setText(String.valueOf(stats.leanRightCount));
            }
            if (uprightCountText != null) {
                uprightCountText.setText(String.valueOf(stats.uprightCount));
            }
            if (avgPdjText != null) {
                avgPdjText.setText(String.format("%.1f", stats.avgPdj));
            }
            if (avgOksText != null) {
                avgOksText.setText(String.format("%.1f", stats.avgOks));
            }
            if (avgInferenceTimeText != null) {
                avgInferenceTimeText.setText(String.format("%.0f ms", stats.avgInferenceTime));
            }
            
            Log.d(TAG, "Dashboard updated with " + stats.totalEntries + " entries");
        } catch (Exception e) {
            Log.e(TAG, "Error in updateUI", e);
            if (!isFinishing()) {
                showError("Error displaying statistics");
            }
        }
    }

    private void showLoading(boolean show) {
        try {
            if (loadingProgress != null) {
                loadingProgress.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in showLoading", e);
        }
    }

    private void showEmptyState(boolean show) {
        try {
            if (emptyState != null) {
                emptyState.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in showEmptyState", e);
        }
    }
    
    private void showError(String message) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            Log.e(TAG, message);
        } catch (Exception e) {
            Log.e(TAG, "Error showing error message", e);
        }
    }

    private enum TimePeriod {
        TODAY, WEEK, MONTH
    }
}
