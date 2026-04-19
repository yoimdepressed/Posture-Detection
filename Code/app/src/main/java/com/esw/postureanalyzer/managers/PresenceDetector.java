package com.esw.postureanalyzer.managers;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Detects user presence based on pose detection results
 * - Switches to "Away" state if no person detected for > 15 seconds
 * - Switches to "Active" state when person is detected
 * - Pauses all timers and logging when "Away"
 */
public class PresenceDetector {
    private static final String TAG = "PresenceDetector";
    private static final long AWAY_THRESHOLD_MS = 15000; // 15 seconds

    private final Handler handler;
    private Runnable awayStateRunnable;
    
    private PresenceState currentState = PresenceState.ACTIVE;
    private long lastPersonDetectedTime = 0;
    private PresenceCallback presenceCallback;

    public enum PresenceState {
        ACTIVE,   // Person is present and being tracked
        AWAY      // No person detected for > 15 seconds
    }

    public interface PresenceCallback {
        void onStateChanged(PresenceState newState);
        void onPersonDetected();
        void onPersonAbsent();
    }

    public PresenceDetector() {
        this.handler = new Handler(Looper.getMainLooper());
        this.lastPersonDetectedTime = System.currentTimeMillis();
    }

    public void setPresenceCallback(PresenceCallback callback) {
        this.presenceCallback = callback;
    }

    /**
     * Call this when a person is detected in the frame
     */
    public void onPersonDetected() {
        lastPersonDetectedTime = System.currentTimeMillis();
        
        // Cancel any pending "away" state transition
        if (awayStateRunnable != null) {
            handler.removeCallbacks(awayStateRunnable);
            awayStateRunnable = null;
        }

        // If we were away, transition back to active
        if (currentState == PresenceState.AWAY) {
            transitionToActive();
        }
        
        if (presenceCallback != null) {
            presenceCallback.onPersonDetected();
        }
    }

    /**
     * Call this when no person is detected in the frame
     */
    public void onNoPersonDetected() {
        // Only schedule away state if we're currently active and haven't already scheduled it
        if (currentState == PresenceState.ACTIVE && awayStateRunnable == null) {
            awayStateRunnable = () -> {
                long timeSinceLastDetection = System.currentTimeMillis() - lastPersonDetectedTime;
                if (timeSinceLastDetection >= AWAY_THRESHOLD_MS) {
                    transitionToAway();
                }
            };
            
            handler.postDelayed(awayStateRunnable, AWAY_THRESHOLD_MS);
        }
        
        if (presenceCallback != null) {
            presenceCallback.onPersonAbsent();
        }
    }

    /**
     * Transition to ACTIVE state
     */
    private void transitionToActive() {
        if (currentState != PresenceState.ACTIVE) {
            Log.d(TAG, "State changed: AWAY -> ACTIVE");
            currentState = PresenceState.ACTIVE;
            
            if (presenceCallback != null) {
                presenceCallback.onStateChanged(PresenceState.ACTIVE);
            }
        }
    }

    /**
     * Transition to AWAY state
     */
    private void transitionToAway() {
        if (currentState != PresenceState.AWAY) {
            Log.d(TAG, "State changed: ACTIVE -> AWAY");
            currentState = PresenceState.AWAY;
            
            if (presenceCallback != null) {
                presenceCallback.onStateChanged(PresenceState.AWAY);
            }
        }
    }

    /**
     * Get current presence state
     */
    public PresenceState getCurrentState() {
        return currentState;
    }

    /**
     * Check if user is currently active
     */
    public boolean isActive() {
        return currentState == PresenceState.ACTIVE;
    }

    /**
     * Check if user is currently away
     */
    public boolean isAway() {
        return currentState == PresenceState.AWAY;
    }

    /**
     * Reset the detector (e.g., on app resume)
     */
    public void reset() {
        if (awayStateRunnable != null) {
            handler.removeCallbacks(awayStateRunnable);
            awayStateRunnable = null;
        }
        lastPersonDetectedTime = System.currentTimeMillis();
        currentState = PresenceState.ACTIVE;
        Log.d(TAG, "Presence detector reset to ACTIVE");
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        if (awayStateRunnable != null) {
            handler.removeCallbacks(awayStateRunnable);
            awayStateRunnable = null;
        }
        handler.removeCallbacksAndMessages(null);
    }
}
