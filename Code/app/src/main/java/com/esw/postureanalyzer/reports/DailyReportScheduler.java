package com.esw.postureanalyzer.reports;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Schedules and executes daily posture report generation and emailing
 */
public class DailyReportScheduler {
    private static final String TAG = "DailyReportScheduler";
    private static final String WORK_NAME = "DailyPostureReport";

    /**
     * Schedule daily report at specified hour (24-hour format)
     */
    public static void scheduleDailyReport(Context context, int hourOfDay) {
        // Calculate initial delay to next occurrence of hourOfDay
        Calendar currentTime = Calendar.getInstance();
        Calendar targetTime = Calendar.getInstance();
        targetTime.set(Calendar.HOUR_OF_DAY, hourOfDay);
        targetTime.set(Calendar.MINUTE, 0);
        targetTime.set(Calendar.SECOND, 0);

        if (targetTime.before(currentTime)) {
            targetTime.add(Calendar.DAY_OF_YEAR, 1);
        }

        long initialDelay = targetTime.getTimeInMillis() - currentTime.getTimeInMillis();

        // Create constraints - require network connection
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        // Create periodic work request (daily)
        PeriodicWorkRequest dailyWorkRequest = new PeriodicWorkRequest.Builder(
                DailyReportWorker.class,
                24, TimeUnit.HOURS,
                30, TimeUnit.MINUTES) // Flex interval
                .setConstraints(constraints)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .build();

        // Schedule the work
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                dailyWorkRequest
        );

        Log.d(TAG, "Daily report scheduled for " + hourOfDay + ":00 (initial delay: " + 
                (initialDelay / 1000 / 60) + " minutes)");
    }

    /**
     * Cancel scheduled daily reports
     */
    public static void cancelDailyReport(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        Log.d(TAG, "Daily report scheduling cancelled");
    }

    /**
     * Check if daily reports are scheduled
     */
    public static boolean isScheduled(Context context) {
        // This is a simplified check - WorkManager doesn't provide easy way to check if work exists
        // For production, you might want to track this in SharedPreferences
        return true; // Placeholder
    }

    /**
     * Worker class that executes the daily report task
     */
    public static class DailyReportWorker extends Worker {
        private static final String TAG = "DailyReportWorker";

        public DailyReportWorker(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            Log.d(TAG, "Starting daily report generation");

            Context context = getApplicationContext();
            EmailService emailService = new EmailService(context);

            // Check if reports are enabled
            if (!emailService.areReportsEnabled()) {
                Log.d(TAG, "Daily reports are disabled");
                return Result.success();
            }

            // Check if email is configured
            if (!emailService.isConfigured()) {
                Log.e(TAG, "Email not configured");
                return Result.failure();
            }

            try {
                // Generate report
                ReportGenerator reportGenerator = new ReportGenerator(context);
                final Object lock = new Object();
                final boolean[] success = {false};
                final String[] errorMsg = {null};

                reportGenerator.generateDailyReport(new ReportGenerator.ReportCallback() {
                    @Override
                    public void onReportGenerated(File pdfFile) {
                        Log.d(TAG, "Report generated: " + pdfFile.getAbsolutePath());

                        // Send email
                        String yesterday = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                                .format(new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000));
                        
                        String subject = "Daily Posture Report - " + yesterday;
                        String body = "Hello,\n\n" +
                                "Please find attached your daily posture analysis report for " + yesterday + ".\n\n" +
                                "This report includes:\n" +
                                "- Summary of total sessions\n" +
                                "- Posture quality percentages\n" +
                                "- Detailed statistics\n" +
                                "- Visual charts\n\n" +
                                "Best regards,\n" +
                                "Posture Analyzer";

                        emailService.sendReport(pdfFile, subject, body, new EmailService.EmailCallback() {
                            @Override
                            public void onEmailSent() {
                                Log.d(TAG, "Report emailed successfully");
                                success[0] = true;
                                synchronized (lock) {
                                    lock.notify();
                                }
                                // Clean up PDF file
                                if (pdfFile.exists()) {
                                    pdfFile.delete();
                                }
                            }

                            @Override
                            public void onError(String error) {
                                Log.e(TAG, "Failed to send email: " + error);
                                errorMsg[0] = error;
                                synchronized (lock) {
                                    lock.notify();
                                }
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Failed to generate report: " + error);
                        errorMsg[0] = error;
                        synchronized (lock) {
                            lock.notify();
                        }
                    }
                });

                // Wait for completion (max 2 minutes)
                synchronized (lock) {
                    lock.wait(120000);
                }

                if (success[0]) {
                    Log.d(TAG, "Daily report task completed successfully");
                    return Result.success();
                } else {
                    Log.e(TAG, "Daily report task failed: " + errorMsg[0]);
                    return Result.retry();
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in daily report worker", e);
                return Result.failure();
            }
        }
    }
}
