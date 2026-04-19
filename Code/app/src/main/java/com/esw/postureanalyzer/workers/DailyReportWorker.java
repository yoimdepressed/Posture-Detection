package com.esw.postureanalyzer.workers;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.esw.postureanalyzer.R;
import com.esw.postureanalyzer.email.EmailService;
import com.esw.postureanalyzer.reports.ReportGenerator;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Worker that generates and emails daily posture reports
 */
public class DailyReportWorker extends Worker {
    private static final String TAG = "DailyReportWorker";
    private static final String CHANNEL_ID = "daily_reports";
    private static final int NOTIFICATION_ID = 1001;

    public DailyReportWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Starting daily report generation");

        Context context = getApplicationContext();
        EmailService emailService = new EmailService(context);

        // Check if email is configured and enabled
        if (!emailService.isEmailEnabled()) {
            Log.d(TAG, "Email reports are disabled");
            return Result.success();
        }

        if (!emailService.isEmailConfigured()) {
            Log.w(TAG, "Email is not configured");
            showNotification("Email Not Configured", 
                "Please configure email settings to receive daily reports");
            return Result.failure();
        }

        // Generate report
        ReportGenerator reportGenerator = new ReportGenerator(context);
        CountDownLatch latch = new CountDownLatch(1);
        final File[] pdfFileHolder = new File[1];
        final String[] errorHolder = new String[1];

        reportGenerator.generateDailyReport(new ReportGenerator.ReportCallback() {
            @Override
            public void onReportGenerated(File pdfFile) {
                pdfFileHolder[0] = pdfFile;
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                errorHolder[0] = error;
                latch.countDown();
            }
        });

        try {
            // Wait for report generation (max 60 seconds)
            if (!latch.await(60, TimeUnit.SECONDS)) {
                Log.e(TAG, "Timeout waiting for report generation");
                showNotification("Report Failed", "Timeout generating daily report");
                return Result.failure();
            }

            if (errorHolder[0] != null) {
                Log.e(TAG, "Error generating report: " + errorHolder[0]);
                showNotification("Report Failed", errorHolder[0]);
                return Result.failure();
            }

            if (pdfFileHolder[0] == null) {
                Log.e(TAG, "PDF file is null");
                showNotification("Report Failed", "Failed to create PDF report");
                return Result.failure();
            }

            // Send email
            CountDownLatch emailLatch = new CountDownLatch(1);
            final boolean[] emailSuccess = new boolean[1];
            final String[] emailErrorHolder = new String[1];

            emailService.sendDailyReport(pdfFileHolder[0], new EmailService.EmailCallback() {
                @Override
                public void onEmailSent() {
                    emailSuccess[0] = true;
                    emailLatch.countDown();
                }

                @Override
                public void onError(String error) {
                    emailErrorHolder[0] = error;
                    emailLatch.countDown();
                }
            });

            // Wait for email sending (max 60 seconds)
            if (!emailLatch.await(60, TimeUnit.SECONDS)) {
                Log.e(TAG, "Timeout waiting for email to send");
                showNotification("Email Failed", "Timeout sending email");
                return Result.failure();
            }

            if (!emailSuccess[0]) {
                Log.e(TAG, "Error sending email: " + emailErrorHolder[0]);
                showNotification("Email Failed", emailErrorHolder[0]);
                return Result.failure();
            }

            // Success
            Log.d(TAG, "Daily report sent successfully");
            showNotification("Report Sent", "Daily posture report has been emailed");
            
            // Clean up PDF file
            if (pdfFileHolder[0] != null && pdfFileHolder[0].exists()) {
                pdfFileHolder[0].delete();
            }

            return Result.success();

        } catch (InterruptedException e) {
            Log.e(TAG, "Worker interrupted", e);
            showNotification("Report Failed", "Worker interrupted");
            return Result.failure();
        }
    }

    private void showNotification(String title, String message) {
        Context context = getApplicationContext();
        NotificationManager notificationManager = 
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Create notification channel for Android O and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Daily Reports",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Notifications for daily posture reports");
            notificationManager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }
}
