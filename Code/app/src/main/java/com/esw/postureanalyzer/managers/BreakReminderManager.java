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
 * Manages break reminders after continuous active time
 * - Tracks cumulative active time
 * - Recommends 1-2 minute break after 25-30 minutes of active time
 * - Pauses tracking when user is away
 */
public class BreakReminderManager {
    private static final String TAG = "BreakReminderManager";
    private static final long BREAK_REMINDER_INTERVAL_MS = 27 * 60 * 1000; // 27 minutes (middle of 25-30 range)
    private static final long ACTIVITY_CHECK_INTERVAL_MS = 1000; // Check every second
    private static final String CHANNEL_ID = "break_reminders";
    private static final int NOTIFICATION_ID = 1002;

    private final Context context;
    private final NotificationManager notificationManager;
    private final Handler handler;
    
    private long totalActiveTimeMs = 0;
    private long sessionStartTime = 0;
    private boolean isTracking = false;
    private Runnable activityCheckRunnable;
    private BreakCallback breakCallback;

    public interface BreakCallback {
        void onBreakRecommended(long activeTimeMinutes);
        void onActiveTimeUpdate(long activeTimeMinutes);
    }

    public BreakReminderManager(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Break Reminders",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Reminders to take regular breaks");
            notificationManager.createNotificationChannel(channel);
        }
    }

    public void setBreakCallback(BreakCallback callback) {
        this.breakCallback = callback;
    }

    /**
     * Start tracking active time
     */
    public void startTracking() {
        if (!isTracking) {
            isTracking = true;
            sessionStartTime = System.currentTimeMillis();
            
            // Start periodic check for break reminder
            scheduleActivityCheck();
            
            Log.d(TAG, "Break reminder tracking started");
        }
    }

    /**
     * Pause tracking (when user is away)
     */
    public void pauseTracking() {
        if (isTracking) {
            // Add the current session time to total
            long sessionDuration = System.currentTimeMillis() - sessionStartTime;
            totalActiveTimeMs += sessionDuration;
            
            isTracking = false;
            sessionStartTime = 0;
            
            // Cancel periodic checks
            if (activityCheckRunnable != null) {
                handler.removeCallbacks(activityCheckRunnable);
                activityCheckRunnable = null;
            }
            
            Log.d(TAG, "Break reminder tracking paused. Total active time: " + (totalActiveTimeMs / 60000) + " minutes");
        }
    }

    /**
     * Resume tracking (when user returns from away)
     */
    public void resumeTracking() {
        if (!isTracking) {
            startTracking();
        }
    }

    /**
     * Schedule periodic activity check
     */
    private void scheduleActivityCheck() {
        activityCheckRunnable = () -> {
            if (isTracking) {
                checkForBreakReminder();
                // Schedule next check
                handler.postDelayed(activityCheckRunnable, ACTIVITY_CHECK_INTERVAL_MS);
            }
        };
        
        handler.postDelayed(activityCheckRunnable, ACTIVITY_CHECK_INTERVAL_MS);
    }

    /**
     * Check if it's time for a break reminder
     */
    private void checkForBreakReminder() {
        long currentActiveTime = getCurrentActiveTimeMs();
        
        // Notify callback of time update (every minute)
        if (currentActiveTime % 60000 < ACTIVITY_CHECK_INTERVAL_MS && breakCallback != null) {
            breakCallback.onActiveTimeUpdate(currentActiveTime / 60000);
        }
        
        // Check if we've reached the break reminder threshold
        if (currentActiveTime >= BREAK_REMINDER_INTERVAL_MS) {
            sendBreakReminder();
            resetActiveTime(); // Reset after sending reminder
        }
    }

    /**
     * Send break reminder notification
     */
    private void sendBreakReminder() {
        Log.d(TAG, "Sending break reminder");
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Time for a Break! 🚶")
                .setContentText("You've been active for 27 minutes. Stand up, walk around, and get some water!")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("You've been active for 27 minutes. Take a 1-2 minute break to:\n" +
                                "• Stand up and stretch\n" +
                                "• Walk around\n" +
                                "• Get some water\n" +
                                "• Rest your eyes"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setVibrate(new long[]{0, 300, 200, 300});
        
        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        builder.setSound(defaultSoundUri);
        
        notificationManager.notify(NOTIFICATION_ID, builder.build());
        
        if (breakCallback != null) {
            breakCallback.onBreakRecommended(getCurrentActiveTimeMs() / 60000);
        }
    }

    /**
     * Get current active time in milliseconds
     */
    public long getCurrentActiveTimeMs() {
        long currentSessionTime = 0;
        if (isTracking) {
            currentSessionTime = System.currentTimeMillis() - sessionStartTime;
        }
        return totalActiveTimeMs + currentSessionTime;
    }

    /**
     * Get current active time in minutes
     */
    public long getCurrentActiveTimeMinutes() {
        return getCurrentActiveTimeMs() / 60000;
    }

    /**
     * Reset active time counter
     */
    public void resetActiveTime() {
        totalActiveTimeMs = 0;
        sessionStartTime = System.currentTimeMillis();
        Log.d(TAG, "Active time reset");
    }

    /**
     * Check if currently tracking
     */
    public boolean isTracking() {
        return isTracking;
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        pauseTracking();
        handler.removeCallbacksAndMessages(null);
    }
}
