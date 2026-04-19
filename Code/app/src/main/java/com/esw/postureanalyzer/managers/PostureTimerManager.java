package com.esw.postureanalyzer.managers;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.esw.postureanalyzer.R;

/**
 * Manages the slouching timer and alerts
 * - Starts timer when slouching is detected
 * - Sends gentle alert after 2-3 minutes of continuous slouching
 * - Resets timer when posture is corrected
 */
public class PostureTimerManager {
    private static final String TAG = "PostureTimerManager";
    private static final long SLOUCH_ALERT_DELAY_MS = 30000; // 30 seconds
    private static final String CHANNEL_ID = "posture_alerts";
    private static final int NOTIFICATION_ID = 1001;

    private final Context context;
    private final NotificationManager notificationManager;
    private final Handler handler;
    private Runnable slouchAlertRunnable;
    
    private boolean isSlouchTimerRunning = false;
    private long slouchStartTime = 0;
    private AlertCallback alertCallback;

    public interface AlertCallback {
        void onSlouchAlert(long slouchDurationMs);
        void onSlouchCorrected(long slouchDurationMs);
    }

    public PostureTimerManager(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Posture Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Gentle reminders to maintain good posture");
            channel.setSound(null, null); // We'll use a soft chime instead
            notificationManager.createNotificationChannel(channel);
        }
    }

    public void setAlertCallback(AlertCallback callback) {
        this.alertCallback = callback;
    }

    /**
     * Call this method when slouching is detected
     */
    public void onSlouchingDetected() {
        if (!isSlouchTimerRunning) {
            isSlouchTimerRunning = true;
            slouchStartTime = System.currentTimeMillis();
            
            // Schedule alert for 2.5 minutes from now
            slouchAlertRunnable = () -> {
                sendGentleAlert();
                if (alertCallback != null) {
                    long duration = System.currentTimeMillis() - slouchStartTime;
                    alertCallback.onSlouchAlert(duration);
                }
            };
            
            handler.postDelayed(slouchAlertRunnable, SLOUCH_ALERT_DELAY_MS);
            Log.d(TAG, "Slouch timer started");
        }
    }

    /**
     * Call this method when good posture is detected
     */
    public void onGoodPostureDetected() {
        if (isSlouchTimerRunning) {
            long slouchDuration = System.currentTimeMillis() - slouchStartTime;
            resetSlouchTimer();
            
            if (alertCallback != null) {
                alertCallback.onSlouchCorrected(slouchDuration);
            }
            
            Log.d(TAG, "Good posture detected, timer reset. Slouched for: " + slouchDuration + "ms");
        }
    }

    /**
     * Reset the slouch timer (called when posture is corrected or user is away)
     */
    public void resetSlouchTimer() {
        if (isSlouchTimerRunning && slouchAlertRunnable != null) {
            handler.removeCallbacks(slouchAlertRunnable);
            slouchAlertRunnable = null;
        }
        isSlouchTimerRunning = false;
        slouchStartTime = 0;
    }

    /**
     * Send a gentle notification and play a soft chime
     */
    private void sendGentleAlert() {
        Log.d(TAG, "Sending gentle slouch alert");
        
        // Create notification with soft appearance
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Posture Reminder 🧘")
                .setContentText("You've been slouching for a while. Time to sit up straight!")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setVibrate(new long[]{0, 200, 100, 200}); // Gentle vibration pattern
        
        // Play a soft notification sound
        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        builder.setSound(defaultSoundUri);
        
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    /**
     * Pause all timers (e.g., when app is in background or user is away)
     */
    public void pauseTimers() {
        resetSlouchTimer();
        Log.d(TAG, "Timers paused");
    }

    /**
     * Get current slouch duration in seconds (for UI display)
     */
    public long getCurrentSlouchDurationSeconds() {
        if (isSlouchTimerRunning) {
            return (System.currentTimeMillis() - slouchStartTime) / 1000;
        }
        return 0;
    }

    /**
     * Check if currently tracking a slouch
     */
    public boolean isTrackingSlouch() {
        return isSlouchTimerRunning;
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        resetSlouchTimer();
        handler.removeCallbacksAndMessages(null);
    }
}
