package com.esw.postureanalyzer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.esw.postureanalyzer.vision.CameraXManager;
import com.esw.postureanalyzer.vision.UnifiedCameraManager;
import com.esw.postureanalyzer.vision.DelegateType;
import com.esw.postureanalyzer.vision.EvaluationMetrics;
import com.esw.postureanalyzer.vision.FirebaseManager;
import com.esw.postureanalyzer.vision.OverlayView;
import com.esw.postureanalyzer.vision.PoseLandmarkerHelper;
import com.esw.postureanalyzer.vision.PostureClassifier;
import com.esw.postureanalyzer.managers.PostureTimerManager;
import com.esw.postureanalyzer.managers.PresenceDetector;
import com.esw.postureanalyzer.managers.BreakReminderManager;
import com.esw.postureanalyzer.managers.StretchSuggestionManager;
import com.esw.postureanalyzer.performance.PerformanceTracker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

public class MainActivity extends AppCompatActivity implements PoseLandmarkerHelper.LandmarkerListener, RadioGroup.OnCheckedChangeListener {
    private static final int CAMERA_PERMISSION_CODE = 100;

    private PreviewView previewView;
    private ImageView usbCameraPreview;
    private OverlayView overlayView;
    private TextView runtimeTextView, metricsTextView, slouchStatusText, legsStatusText, leanStatusText;
    private TextView presenceStatusText, activeTimeText, slouchTimerText, cameraStatusText;
    private RadioGroup delegateRadioGroup;
    private Button cameraSwitchButton;
    private Button toggleStatsButton;
    private TextView performanceStatsText;
    private android.widget.ScrollView performanceStatsScroll;
    private boolean showDetailedStats = false;

    private CameraXManager cameraXManager;
    private UnifiedCameraManager unifiedCameraManager;
    private PoseLandmarkerHelper poseLandmarkerHelper;
    private PostureClassifier postureClassifier;
    private FirebaseManager firebaseManager;
    private PerformanceTracker performanceTracker;
    
    // New managers for enhanced features
    private PostureTimerManager postureTimerManager;
    private PresenceDetector presenceDetector;
    private BreakReminderManager breakReminderManager;
    private StretchSuggestionManager stretchSuggestionManager;
    
    private boolean hasShownSlouchStretch = false; // Prevent multiple stretch dialogs

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.preview_view);
        // Set PreviewView to FIT mode to match ImageView fitCenter behavior
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        usbCameraPreview = findViewById(R.id.usb_camera_preview);
        overlayView = findViewById(R.id.overlay);
        runtimeTextView = findViewById(R.id.runtime_text_view);
        metricsTextView = findViewById(R.id.metrics_text_view);
        slouchStatusText = findViewById(R.id.slouch_status_text);
        legsStatusText = findViewById(R.id.legs_status_text);
        leanStatusText = findViewById(R.id.lean_status_text);
        presenceStatusText = findViewById(R.id.presence_status_text);
        activeTimeText = findViewById(R.id.active_time_text);
        slouchTimerText = findViewById(R.id.slouch_timer_text);
        cameraStatusText = findViewById(R.id.camera_status_text);
        delegateRadioGroup = findViewById(R.id.delegate_radio_group);
        
        // Performance stats views
        toggleStatsButton = findViewById(R.id.toggle_stats_button);
        performanceStatsText = findViewById(R.id.performance_stats_text);
        performanceStatsScroll = findViewById(R.id.performance_stats_scroll);

        // Dashboard button
        Button dashboardButton = findViewById(R.id.dashboard_button);
        dashboardButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, DashboardActivity.class);
            startActivity(intent);
        });

        // Camera switch button
        cameraSwitchButton = findViewById(R.id.camera_switch_button);
        if (cameraSwitchButton != null) {
            cameraSwitchButton.setOnClickListener(v -> switchCameraSource());
        }
        
        // Toggle stats button
        if (toggleStatsButton != null) {
            toggleStatsButton.setOnClickListener(v -> {
                showDetailedStats = !showDetailedStats;
                updatePerformanceDisplay();
            });
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        } else {
            initializeApp();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializeApp();
        } else {
            Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_SHORT).show();
        }
    }

    private void initializeApp() {
        poseLandmarkerHelper = new PoseLandmarkerHelper(this, this);
        postureClassifier = new PostureClassifier(this);
        
        firebaseManager = new FirebaseManager();
        performanceTracker = new PerformanceTracker(this);

        // Initialize UI with default values
        initializeUI();

        // Initialize new managers
        initializeManagers();

        // Test Firebase connection on app start
        testFirebaseConnection();

        // Initialize unified camera manager
        unifiedCameraManager = new UnifiedCameraManager(this, (bitmap, rotationDegrees) -> {
            if (poseLandmarkerHelper != null) {
                poseLandmarkerHelper.detectLiveStream(bitmap, rotationDegrees);
            }
        });

        unifiedCameraManager.setStatusListener(new UnifiedCameraManager.CameraStatusListener() {
            @Override
            public void onCameraStarted(UnifiedCameraManager.CameraType type) {
                runOnUiThread(() -> {
                    String cameraName = type == UnifiedCameraManager.CameraType.USB_UVC ? "USB Camera" : "Internal Camera";
                    if (cameraStatusText != null) {
                        cameraStatusText.setText("Camera: " + cameraName);
                    }
                    
                    // Ensure correct camera preview is visible
                    if (type == UnifiedCameraManager.CameraType.USB_UVC) {
                        if (previewView != null) previewView.setVisibility(View.GONE);
                        if (usbCameraPreview != null) usbCameraPreview.setVisibility(View.VISIBLE);
                        Toast.makeText(MainActivity.this, 
                            "✓ USB Camera Active\nStreaming via V4L2 (/dev/video2)", 
                            Toast.LENGTH_SHORT).show();
                    } else {
                        if (usbCameraPreview != null) usbCameraPreview.setVisibility(View.GONE);
                        if (previewView != null) previewView.setVisibility(View.VISIBLE);
                        Toast.makeText(MainActivity.this, cameraName + " started", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onCameraStopped(UnifiedCameraManager.CameraType type) {
                runOnUiThread(() -> {
                    if (cameraStatusText != null) {
                        cameraStatusText.setText("Camera: Stopped");
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    // Don't show error for USB camera since it's expected
                    if (!message.contains("Invalid camera type")) {
                        Toast.makeText(MainActivity.this, "Camera error: " + message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });

        // Keep legacy camera manager for compatibility
        cameraXManager = new CameraXManager(this, previewView, (bitmap, rotationDegrees) -> {
            if (poseLandmarkerHelper != null) {
                poseLandmarkerHelper.detectLiveStream(bitmap, rotationDegrees);
            }
        });
        
        delegateRadioGroup.setOnCheckedChangeListener(this);
        
        // Set CPU as default delegate on startup
        setDefaultDelegate();
    }
    
    /**
     * Set CPU as the default delegate on app startup
     */
    private void setDefaultDelegate() {
        // Set CPU radio button as checked
        RadioButton cpuRadio = findViewById(R.id.delegate_cpu);
        if (cpuRadio != null) {
            cpuRadio.setChecked(true);
        }
        
        // Initialize delegates to CPU
        if (poseLandmarkerHelper != null) {
            poseLandmarkerHelper.setCurrentDelegate(PoseLandmarkerHelper.DELEGATE_CPU);
        }
        if (postureClassifier != null) {
            postureClassifier.setDelegate(DelegateType.CPU);
        }
        
        // Start initial tracking session with actual load times
        if (performanceTracker != null && postureClassifier != null) {
            long loadTime = postureClassifier.getLastModelLoadTimeMs();
            long warmupTime = postureClassifier.getLastWarmupTimeMs();
            performanceTracker.startSession(DelegateType.CPU, loadTime, warmupTime);
        }
        
        Log.d("MainActivity", "Default delegate set to CPU");
    }

    /**
     * Switch between internal camera and USB camera
     */
    private void switchCameraSource() {
        if (unifiedCameraManager == null) {
            Toast.makeText(this, "Camera manager not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        UnifiedCameraManager.CameraType currentType = unifiedCameraManager.getCurrentCameraType();
        
        if (currentType == UnifiedCameraManager.CameraType.INTERNAL) {
            // Switch to USB camera
            // IMPORTANT: Hide PreviewView and show USB ImageView
            if (previewView != null) {
                previewView.setVisibility(View.GONE);
            }
            if (usbCameraPreview != null) {
                usbCameraPreview.setVisibility(View.VISIBLE);
            }
            unifiedCameraManager.switchCamera(UnifiedCameraManager.CameraType.USB_UVC, usbCameraPreview);
            if (cameraSwitchButton != null) {
                cameraSwitchButton.setText("Switch to Internal");
            }
        } else {
            // Switch to internal camera
            // IMPORTANT: Show PreviewView and hide USB ImageView
            if (usbCameraPreview != null) {
                usbCameraPreview.setVisibility(View.GONE);
            }
            if (previewView != null) {
                previewView.setVisibility(View.VISIBLE);
            }
            unifiedCameraManager.switchCamera(UnifiedCameraManager.CameraType.INTERNAL, previewView);
            if (cameraSwitchButton != null) {
                cameraSwitchButton.setText("Switch to USB");
            }
        }
    }

    /**
     * Initialize UI with default values
     */
    private void initializeUI() {
        // Set initial status
        if (presenceStatusText != null) {
            presenceStatusText.setText("Status: Active");
            presenceStatusText.setTextColor(getColor(android.R.color.holo_green_light));
        }
        
        // Set initial camera status
        if (cameraStatusText != null) {
            cameraStatusText.setText("Camera: Internal Camera");
        }
        
        // Set initial camera switch button text
        if (cameraSwitchButton != null) {
            cameraSwitchButton.setText("Switch to USB");
        }
        
        // Set initial active time
        if (activeTimeText != null) {
            activeTimeText.setText("Active: 0 min");
        }
        
        // Hide slouch timer initially
        if (slouchTimerText != null) {
            slouchTimerText.setVisibility(View.GONE);
        }
    }

    /**
     * Initialize all feature managers
     */
    private void initializeManagers() {
        // Posture Timer Manager
        postureTimerManager = new PostureTimerManager(this);
        postureTimerManager.setAlertCallback(new PostureTimerManager.AlertCallback() {
            @Override
            public void onSlouchAlert(long slouchDurationMs) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, 
                        "Slouch Alert: You've been slouching for " + (slouchDurationMs / 1000) + " seconds", 
                        Toast.LENGTH_SHORT).show();
                    
                    // Show stretch suggestion when slouch alert triggers
                    if (!hasShownSlouchStretch && stretchSuggestionManager != null) {
                        stretchSuggestionManager.showChestOpenerStretch();
                        hasShownSlouchStretch = true;
                    }
                });
            }

            @Override
            public void onSlouchCorrected(long slouchDurationMs) {
                runOnUiThread(() -> {
                    Log.d("MainActivity", "Posture corrected after " + (slouchDurationMs / 1000) + " seconds");
                    hasShownSlouchStretch = false; // Reset so we can show again next time
                });
            }
        });

        // Presence Detector
        presenceDetector = new PresenceDetector();
        presenceDetector.setPresenceCallback(new PresenceDetector.PresenceCallback() {
            @Override
            public void onStateChanged(PresenceDetector.PresenceState newState) {
                runOnUiThread(() -> {
                    if (newState == PresenceDetector.PresenceState.AWAY) {
                        presenceStatusText.setText("Status: Away");
                        presenceStatusText.setTextColor(getColor(android.R.color.holo_orange_light));
                        
                        // Pause all timers when away
                        postureTimerManager.pauseTimers();
                        breakReminderManager.pauseTracking();
                        
                        Toast.makeText(MainActivity.this, "Away mode - Timers paused", Toast.LENGTH_SHORT).show();
                    } else {
                        presenceStatusText.setText("Status: Active");
                        presenceStatusText.setTextColor(getColor(android.R.color.holo_green_light));
                        
                        // Resume tracking when active
                        breakReminderManager.resumeTracking();
                        
                        Toast.makeText(MainActivity.this, "Active mode - Timers resumed", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onPersonDetected() {
                // Person is in frame
                runOnUiThread(() -> {
                    // Update status immediately when person detected
                    if (presenceStatusText != null && presenceDetector != null && presenceDetector.isActive()) {
                        presenceStatusText.setText("Status: Active");
                        presenceStatusText.setTextColor(getColor(android.R.color.holo_green_light));
                    }
                });
            }

            @Override
            public void onPersonAbsent() {
                // Person left frame
                runOnUiThread(() -> {
                    // Optionally show intermediate state
                    Log.d("MainActivity", "Person absent from frame");
                });
            }
        });

        // Break Reminder Manager
        breakReminderManager = new BreakReminderManager(this);
        breakReminderManager.setBreakCallback(new BreakReminderManager.BreakCallback() {
            @Override
            public void onBreakRecommended(long activeTimeMinutes) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, 
                        "Break Recommended! You've been active for " + activeTimeMinutes + " minutes", 
                        Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onActiveTimeUpdate(long activeTimeMinutes) {
                runOnUiThread(() -> {
                    if (activeTimeText != null) {
                        activeTimeText.setText(String.format("Active: %d min", activeTimeMinutes));
                        Log.d("MainActivity", "Active time updated: " + activeTimeMinutes + " min");
                    }
                });
            }
        });
        breakReminderManager.startTracking();
        
        // Immediately update the active time display
        runOnUiThread(() -> {
            if (activeTimeText != null) {
                long currentMinutes = breakReminderManager.getCurrentActiveTimeMinutes();
                activeTimeText.setText(String.format("Active: %d min", currentMinutes));
            }
        });

        // Stretch Suggestion Manager
        stretchSuggestionManager = new StretchSuggestionManager(this);
    }

    /**
     * Test Firebase connection and show result to user
     */
    private void testFirebaseConnection() {
        firebaseManager.testConnection(new FirebaseManager.ConnectionTestCallback() {
            @Override
            public void onConnectionSuccess(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, 
                        "✓ Firebase Connected", 
                        Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onConnectionFailure(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, 
                        "✗ Firebase Error: " + error, 
                        Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    public void onResults(PoseLandmarkerHelper.ResultBundle resultBundle) {
        PostureClassifier.ClassificationResult classificationResult = null;
        String metricsString = "PDJ / OKS: N/A";

        if (resultBundle.getResults().landmarks().size() > 0) {
            // Person detected - notify presence detector
            if (presenceDetector != null) {
                presenceDetector.onPersonDetected();
                Log.d("MainActivity", "Person detected - State: " + presenceDetector.getCurrentState());
            }

            // Classify posture using TFLite
            classificationResult = postureClassifier.classify(
                    resultBundle.getResults(),
                    resultBundle.getInputImageWidth(),
                    resultBundle.getInputImageHeight()
            );
            
            // Track and upload performance data with throttling
            if (performanceTracker != null && classificationResult != null) {
                // Use TOTAL inference time (PoseLandmarker + classification models)
                // resultBundle.getInferenceTime() already includes the pose detection time
                long totalInferenceTimeMicros = resultBundle.getInferenceTime();
                performanceTracker.recordInference(totalInferenceTimeMicros);
                
                // Calculate FPS from total time
                if (totalInferenceTimeMicros > 0) {
                    double fps = 1_000_000.0 / totalInferenceTimeMicros; // Convert μs to FPS
                    performanceTracker.recordFps(fps);
                }
                
                // Upload performance data for every frame
                uploadPerformanceData();
            }

            // Handle posture timer based on slouching status
            if (classificationResult != null && postureTimerManager != null) {
                String slouchStatus = classificationResult.getSlouchStatus();
                if ("Slouching".equals(slouchStatus)) {
                    postureTimerManager.onSlouchingDetected();
                } else if ("Good Posture".equals(slouchStatus)) {
                    postureTimerManager.onGoodPostureDetected();
                }
            }

            // Calculate evaluation metrics
            metricsString = EvaluationMetrics.getQualityMetrics(
                    resultBundle.getResults().landmarks().get(0)
            );

            // Log data to Firebase only when active
            if (presenceDetector != null && presenceDetector.isActive()) {
                firebaseManager.logDataWithMetrics(
                        classificationResult, 
                        resultBundle.getResults().landmarks().get(0),
                        metricsString,
                        resultBundle.getInferenceTime()
                );
            }
        } else {
            // No person detected - notify presence detector
            if (presenceDetector != null) {
                presenceDetector.onNoPersonDetected();
                Log.d("MainActivity", "No person detected - State: " + presenceDetector.getCurrentState());
            }
        }

        final PostureClassifier.ClassificationResult finalResult = classificationResult;
        final String finalMetrics = metricsString;

        runOnUiThread(() -> {
            // Convert from microseconds to milliseconds
            long totalTimeMs = resultBundle.getInferenceTime() / 1000;
            long classifierTimeMicros = postureClassifier.getLastInferenceTimeMicros();
            long classifierTimeMs = classifierTimeMicros / 1000;
            long mediapipeTimeMs = totalTimeMs - classifierTimeMs;
            
            String runtimeLabel = "TFL";
            runtimeTextView.setText(String.format("Runtime: %d ms (MP:%d + %s:%d)", 
                totalTimeMs, mediapipeTimeMs, runtimeLabel, classifierTimeMs));
            metricsTextView.setText(finalMetrics);
            
            // Update detailed performance stats if enabled
            if (showDetailedStats) {
                updatePerformanceDisplay();
            }

            // Always update presence status to reflect current state
            if (presenceDetector != null && presenceStatusText != null) {
                if (presenceDetector.isActive()) {
                    presenceStatusText.setText("Status: Active");
                    presenceStatusText.setTextColor(getColor(android.R.color.holo_green_light));
                } else {
                    presenceStatusText.setText("Status: Away");
                    presenceStatusText.setTextColor(getColor(android.R.color.holo_orange_light));
                }
            }

            if (finalResult != null) {
                slouchStatusText.setText(String.format("Posture: %s", finalResult.getSlouchStatus()));
                legsStatusText.setText(String.format("Legs: %s", finalResult.getLegsStatus()));
                leanStatusText.setText(String.format("Lean: %s", finalResult.getLeanStatus()));
                
                // Update slouch timer display
                if (postureTimerManager != null && postureTimerManager.isTrackingSlouch()) {
                    long slouchSeconds = postureTimerManager.getCurrentSlouchDurationSeconds();
                    slouchTimerText.setVisibility(View.VISIBLE);
                    slouchTimerText.setText(String.format("Slouching: %ds", slouchSeconds));
                } else {
                    slouchTimerText.setVisibility(View.GONE);
                }
            } else {
                slouchStatusText.setText("Posture: --");
                legsStatusText.setText("Legs: --");
                leanStatusText.setText("Lean: --");
                slouchTimerText.setVisibility(View.GONE);
            }

            // Always update overlay - it will handle empty results
            overlayView.setResults(
                    resultBundle.getResults(),
                    resultBundle.getInputImageHeight(),
                    resultBundle.getInputImageWidth()
            );
        });
    }

    @Override
    public void onError(String error) {
        runOnUiThread(() -> Toast.makeText(this, error, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onCheckedChanged(RadioGroup group, int checkedId) {
        DelegateType newDelegate = null;
        
        // TFLite delegates
        if (checkedId == R.id.delegate_cpu) {
            newDelegate = DelegateType.CPU;
            poseLandmarkerHelper.setCurrentDelegate(PoseLandmarkerHelper.DELEGATE_CPU);
            postureClassifier.setDelegate(DelegateType.CPU);
            Log.d("MainActivity", "TFLite delegate switched to CPU");
        } else if (checkedId == R.id.delegate_gpu) {
            newDelegate = DelegateType.GPU;
            Log.d("MainActivity", "User selected TFLite GPU delegate");
            try {
                poseLandmarkerHelper.setCurrentDelegate(PoseLandmarkerHelper.DELEGATE_GPU);
                postureClassifier.setDelegate(DelegateType.GPU);
                Log.d("MainActivity", "✓ TFLite GPU delegate set successfully");
            } catch (Exception e) {
                Log.e("MainActivity", "✗ Failed to switch to TFLite GPU", e);
                Toast.makeText(this, "GPU failed - Falling back to CPU", Toast.LENGTH_LONG).show();
                RadioButton cpuButton = findViewById(R.id.delegate_cpu);
                if (cpuButton != null) {
                    cpuButton.setChecked(true);
                }
                return;
            }
        } else if (checkedId == R.id.delegate_nnapi) {
            newDelegate = DelegateType.NNAPI;
            postureClassifier.setDelegate(DelegateType.NNAPI);
            Log.d("MainActivity", "TFLite delegate switched to NNAPI");
        }
        
        if (newDelegate != null) {
            
            // Start new tracking session with actual load times
            if (performanceTracker != null) {
                long loadTime = postureClassifier.getLastModelLoadTimeMs();
                long warmupTime = postureClassifier.getLastWarmupTimeMs();
                postureClassifier.resetPerformanceMonitors();
                
                performanceTracker.startSession(newDelegate, loadTime, warmupTime);
            }
            
            Toast.makeText(this, "Switched to " + newDelegate.getDisplayName(), 
                          Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            // Start with internal camera by default (unified camera manager handles this)
            if (unifiedCameraManager != null) {
                unifiedCameraManager.startInternalCamera(previewView);
            } else if (cameraXManager != null) {
                // Fallback to legacy camera manager
                cameraXManager.startCamera();
            }
            
            if (poseLandmarkerHelper != null) {
                poseLandmarkerHelper.setupPoseLandmarker();
            }
            if (presenceDetector != null) {
                presenceDetector.reset();
            }
            if (breakReminderManager != null && !breakReminderManager.isTracking()) {
                breakReminderManager.startTracking();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (unifiedCameraManager != null) {
            unifiedCameraManager.stopCamera();
        }
        if (poseLandmarkerHelper != null) {
            poseLandmarkerHelper.clearPoseLandmarker();
        }
        if (postureTimerManager != null) {
            postureTimerManager.pauseTimers();
        }
        if (breakReminderManager != null) {
            breakReminderManager.pauseTracking();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (unifiedCameraManager != null) {
            unifiedCameraManager.release();
        }
        if (postureClassifier != null) {
            postureClassifier.close();
        }
        if (postureTimerManager != null) {
            postureTimerManager.cleanup();
        }
        if (presenceDetector != null) {
            presenceDetector.cleanup();
        }
        if (breakReminderManager != null) {
            breakReminderManager.cleanup();
        }
        if (stretchSuggestionManager != null) {
            stretchSuggestionManager.dismissCurrentDialog();
        }
    }
    
    /**
     * Update performance display based on toggle state
     */
    private void updatePerformanceDisplay() {
        if (showDetailedStats && performanceStatsScroll != null && performanceStatsText != null) {
            String postureStats = postureClassifier.getPerformanceStats();
            String landmarkerStats = poseLandmarkerHelper.getPerformanceStats();
            
            String stats = String.format(
                "=== PERFORMANCE COMPARISON ===\n\n" +
                "Current Delegate: %s\n\n" +
                "POSE LANDMARKER:\n%s\n\n" +
                "POSTURE CLASSIFIERS:\n%s",
                postureClassifier.getCurrentDelegate().getDisplayName(),
                landmarkerStats,
                postureStats
            );
            
            Log.d("MainActivity", "Updating performance display");
            Log.d("MainActivity", "Posture stats: " + postureStats);
            Log.d("MainActivity", "Landmarker stats: " + landmarkerStats);
            
            performanceStatsText.setText(stats);
            performanceStatsScroll.setVisibility(View.VISIBLE);
            toggleStatsButton.setText("Hide Stats");
        } else if (performanceStatsScroll != null && toggleStatsButton != null) {
            performanceStatsScroll.setVisibility(View.GONE);
            toggleStatsButton.setText("Show Stats");
        }
    }
    
    /**
     * Upload performance data to Firebase automatically
     */
    private void uploadPerformanceData() {
        if (performanceTracker == null) {
            return;
        }
        
        // Set model info before uploading
        performanceTracker.setModelInfo("posture_models_combined", 21504); // 3.4KB + 5.0KB + 13.1KB
        
        // Upload session data (runs async)
        performanceTracker.uploadSession();
        
        // Upload detailed stats shown in UI
        uploadDetailedStats();
        
        Log.d("MainActivity", "Auto-uploading performance data to Firebase");
    }
    
    /**
     * Upload all detailed performance stats displayed in UI to Firebase
     */
    private void uploadDetailedStats() {
        if (firebaseManager == null || postureClassifier == null || poseLandmarkerHelper == null) {
            Log.w("MainActivity", "Cannot upload: firebaseManager=" + (firebaseManager != null) + 
                  ", postureClassifier=" + (postureClassifier != null) + 
                  ", poseLandmarkerHelper=" + (poseLandmarkerHelper != null));
            return;
        }
        
        Log.d("MainActivity", "Starting uploadDetailedStats...");
        try {
            // Get all the stats shown in the UI
            String postureStats = postureClassifier.getPerformanceStats();
            String landmarkerStats = poseLandmarkerHelper.getPerformanceStats();
            String currentDelegate = postureClassifier.getCurrentDelegate().getDisplayName();
            
            // Create detailed stats map
            java.util.Map<String, Object> detailedStats = new java.util.HashMap<>();
            
            // Metadata
            detailedStats.put("timestamp", System.currentTimeMillis());
            detailedStats.put("date", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date()));
            detailedStats.put("delegate", currentDelegate);
            
            // Camera info
            String cameraType = unifiedCameraManager != null ? 
                unifiedCameraManager.getCurrentCameraType().name() : "UNKNOWN";
            detailedStats.put("cameraType", cameraType);
            
            // Raw stats text (as shown in UI)
            detailedStats.put("postureClassifierStats", postureStats);
            detailedStats.put("poseLandmarkerStats", landmarkerStats);
            
            // Individual model metrics (NEW!)
            java.util.Map<String, Object> individualModels = postureClassifier.getIndividualModelMetrics();
            detailedStats.put("individualModels", individualModels);
            
            // Device info
            String deviceModel = android.os.Build.MODEL;
            String deviceManufacturer = android.os.Build.MANUFACTURER;
            String deviceId = deviceManufacturer + "_" + deviceModel.replaceAll("[^a-zA-Z0-9]", "_");
            
            detailedStats.put("deviceModel", deviceModel);
            detailedStats.put("deviceManufacturer", deviceManufacturer);
            detailedStats.put("deviceId", deviceId);
            detailedStats.put("androidVersion", android.os.Build.VERSION.RELEASE);
            
            Log.d("MainActivity", "Uploading to Firebase for device: " + deviceId);
            Log.d("MainActivity", "Individual models data size: " + individualModels.size());
            
            // Upload to Firebase organized by device: device_performance_data/{deviceId}/{timestamp}
            com.google.firebase.database.FirebaseDatabase database = 
                com.google.firebase.database.FirebaseDatabase.getInstance(
                    "https://postureanalyzer-b24a3-default-rtdb.asia-southeast1.firebasedatabase.app");
            database.getReference("device_performance_data")
                .child(deviceId)
                .push()
                .setValue(detailedStats)
                .addOnSuccessListener(aVoid -> 
                    Log.d("MainActivity", "✓ Detailed stats uploaded to Firebase for device: " + deviceId))
                .addOnFailureListener(e -> {
                    Log.e("MainActivity", "✗ Failed to upload detailed stats: " + e.getMessage());
                    e.printStackTrace();
                });
                    
        } catch (Exception e) {
            Log.e("MainActivity", "Error uploading detailed stats", e);
        }
    }
}