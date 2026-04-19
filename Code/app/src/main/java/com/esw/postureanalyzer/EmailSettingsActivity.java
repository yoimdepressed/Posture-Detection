package com.esw.postureanalyzer;

import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.esw.postureanalyzer.email.EmailService;
import com.esw.postureanalyzer.workers.DailyReportWorker;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.concurrent.TimeUnit;

/**
 * Activity for configuring email settings for daily reports
 */
public class EmailSettingsActivity extends AppCompatActivity {
    private static final String TAG = "EmailSettingsActivity";
    private static final String WORK_NAME = "daily_report_work";

    private TextInputEditText etSenderEmail;
    private TextInputEditText etSenderPassword;
    private TextInputEditText etRecipientEmail;
    private TextInputEditText etSmtpHost;
    private TextInputEditText etSmtpPort;
    private Switch switchEnableEmail;
    private Button btnSave;
    private Button btnTestEmail;

    private EmailService emailService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_email_settings);

        // Enable back button
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Email Settings");
        }

        emailService = new EmailService(this);

        // Initialize views
        etSenderEmail = findViewById(R.id.etSenderEmail);
        etSenderPassword = findViewById(R.id.etSenderPassword);
        etRecipientEmail = findViewById(R.id.etRecipientEmail);
        etSmtpHost = findViewById(R.id.etSmtpHost);
        etSmtpPort = findViewById(R.id.etSmtpPort);
        switchEnableEmail = findViewById(R.id.switchEnableEmail);
        btnSave = findViewById(R.id.btnSave);
        btnTestEmail = findViewById(R.id.btnTestEmail);

        // Load current configuration
        loadEmailConfig();

        // Set up listeners
        btnSave.setOnClickListener(v -> saveEmailConfig());
        btnTestEmail.setOnClickListener(v -> testEmailConfig());
        switchEnableEmail.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !emailService.isEmailConfigured()) {
                Toast.makeText(this, "Please configure email settings first", Toast.LENGTH_SHORT).show();
                switchEnableEmail.setChecked(false);
            }
        });
    }

    private void loadEmailConfig() {
        EmailService.EmailConfig config = emailService.getEmailConfig();
        etSenderEmail.setText(config.senderEmail);
        etRecipientEmail.setText(config.recipientEmail);
        etSmtpHost.setText(config.smtpHost);
        etSmtpPort.setText(config.smtpPort);
        switchEnableEmail.setChecked(emailService.isEmailEnabled());
    }

    private void saveEmailConfig() {
        String senderEmail = etSenderEmail.getText().toString().trim();
        String senderPassword = etSenderPassword.getText().toString().trim();
        String recipientEmail = etRecipientEmail.getText().toString().trim();
        String smtpHost = etSmtpHost.getText().toString().trim();
        String smtpPort = etSmtpPort.getText().toString().trim();

        // Validate inputs
        if (senderEmail.isEmpty()) {
            Toast.makeText(this, "Please enter sender email", Toast.LENGTH_SHORT).show();
            return;
        }

        if (senderPassword.isEmpty()) {
            Toast.makeText(this, "Please enter sender password", Toast.LENGTH_SHORT).show();
            return;
        }

        if (recipientEmail.isEmpty()) {
            Toast.makeText(this, "Please enter recipient email", Toast.LENGTH_SHORT).show();
            return;
        }

        if (smtpHost.isEmpty()) {
            smtpHost = "smtp.gmail.com";
        }

        if (smtpPort.isEmpty()) {
            smtpPort = "587";
        }

        // Save configuration
        emailService.saveEmailConfig(senderEmail, senderPassword, recipientEmail, smtpHost, smtpPort);
        emailService.setEmailEnabled(switchEnableEmail.isChecked());

        // Schedule or cancel daily reports
        if (switchEnableEmail.isChecked()) {
            scheduleDailyReports();
        } else {
            cancelDailyReports();
        }

        Toast.makeText(this, "Email settings saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void testEmailConfig() {
        // First save the current settings
        String senderEmail = etSenderEmail.getText().toString().trim();
        String senderPassword = etSenderPassword.getText().toString().trim();
        String recipientEmail = etRecipientEmail.getText().toString().trim();
        String smtpHost = etSmtpHost.getText().toString().trim();
        String smtpPort = etSmtpPort.getText().toString().trim();

        // Validate
        if (senderEmail.isEmpty() || senderPassword.isEmpty() || recipientEmail.isEmpty()) {
            Toast.makeText(this, "Please fill in all required fields first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (smtpHost.isEmpty()) smtpHost = "smtp.gmail.com";
        if (smtpPort.isEmpty()) smtpPort = "587";

        // Save temporarily
        emailService.saveEmailConfig(senderEmail, senderPassword, recipientEmail, smtpHost, smtpPort);

        // Show progress dialog
        AlertDialog progressDialog = new AlertDialog.Builder(this)
            .setTitle("Sending Test Email")
            .setMessage("Generating report and sending email...\nThis may take up to 30 seconds.")
            .setCancelable(false)
            .create();
        progressDialog.show();

        // Generate a test report and send it
        new Thread(() -> {
            try {
                // Generate report for last 7 days (to ensure we have data)
                com.esw.postureanalyzer.reports.ReportGenerator reportGenerator = 
                    new com.esw.postureanalyzer.reports.ReportGenerator(this);
                
                final boolean[] success = {false};
                final String[] errorMsg = {null};
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

                // Get data from last 7 days
                java.util.Calendar calendar = java.util.Calendar.getInstance();
                calendar.add(java.util.Calendar.DAY_OF_YEAR, -7);
                calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
                calendar.set(java.util.Calendar.MINUTE, 0);
                calendar.set(java.util.Calendar.SECOND, 0);
                long startTime = calendar.getTimeInMillis();
                
                calendar = java.util.Calendar.getInstance();
                long endTime = calendar.getTimeInMillis();

                reportGenerator.generateReport(startTime, endTime, "Test Report - Last 7 Days", new com.esw.postureanalyzer.reports.ReportGenerator.ReportCallback() {
                    @Override
                    public void onReportGenerated(java.io.File pdfFile) {
                        // Now send the email
                        EmailService emailSvc = new EmailService(EmailSettingsActivity.this);
                        emailSvc.sendDailyReport(pdfFile, new EmailService.EmailCallback() {
                            @Override
                            public void onEmailSent() {
                                success[0] = true;
                                latch.countDown();
                            }

                            @Override
                            public void onError(String error) {
                                errorMsg[0] = error;
                                latch.countDown();
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        errorMsg[0] = error;
                        latch.countDown();
                    }
                });

                // Wait for completion
                latch.await(60, java.util.concurrent.TimeUnit.SECONDS);

                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (success[0]) {
                        new AlertDialog.Builder(this)
                            .setTitle("✅ Test Successful!")
                            .setMessage("Test email sent successfully!\n\nCheck your inbox at:\n" + recipientEmail)
                            .setPositiveButton("OK", null)
                            .show();
                    } else {
                        new AlertDialog.Builder(this)
                            .setTitle("❌ Test Failed")
                            .setMessage("Error: " + (errorMsg[0] != null ? errorMsg[0] : "Unknown error"))
                            .setPositiveButton("OK", null)
                            .show();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    new AlertDialog.Builder(this)
                        .setTitle("❌ Test Failed")
                        .setMessage("Error: " + e.getMessage() + "\n\nStack trace logged to console.")
                        .setPositiveButton("OK", null)
                        .show();
                });
                android.util.Log.e("EmailSettingsActivity", "Test email failed", e);
            }
        }).start();
    }

    private void scheduleDailyReports() {
        // Schedule daily work at 9:00 AM
        PeriodicWorkRequest dailyWorkRequest = new PeriodicWorkRequest.Builder(
                DailyReportWorker.class,
                1, TimeUnit.DAYS  // Repeat every 24 hours
        )
        .setInitialDelay(calculateInitialDelay(), TimeUnit.MILLISECONDS)
        .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                dailyWorkRequest
        );

        Toast.makeText(this, "Daily reports scheduled for 9:00 AM", Toast.LENGTH_SHORT).show();
    }

    private void cancelDailyReports() {
        WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME);
        Toast.makeText(this, "Daily reports cancelled", Toast.LENGTH_SHORT).show();
    }

    /**
     * Calculate delay until next 9:00 AM
     */
    private long calculateInitialDelay() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        java.util.Calendar target = java.util.Calendar.getInstance();
        
        // Set target to 9:00 AM today
        target.set(java.util.Calendar.HOUR_OF_DAY, 9);
        target.set(java.util.Calendar.MINUTE, 0);
        target.set(java.util.Calendar.SECOND, 0);
        target.set(java.util.Calendar.MILLISECOND, 0);

        // If 9:00 AM has passed today, schedule for tomorrow
        if (calendar.after(target)) {
            target.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }

        return target.getTimeInMillis() - calendar.getTimeInMillis();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
