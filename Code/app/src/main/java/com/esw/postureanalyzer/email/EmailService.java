package com.esw.postureanalyzer.email;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

/**
 * Service to send emails with PDF attachments
 */
public class EmailService {
    private static final String TAG = "EmailService";
    private static final String PREFS_NAME = "EmailPrefs";
    private static final String KEY_SENDER_EMAIL = "sender_email";
    private static final String KEY_SENDER_PASSWORD = "sender_password";
    private static final String KEY_RECIPIENT_EMAIL = "recipient_email";
    private static final String KEY_SMTP_HOST = "smtp_host";
    private static final String KEY_SMTP_PORT = "smtp_port";
    private static final String KEY_EMAIL_ENABLED = "email_enabled";

    private final Context context;

    public EmailService(Context context) {
        this.context = context;
    }

    public interface EmailCallback {
        void onEmailSent();
        void onError(String error);
    }

    /**
     * Send email with PDF attachment
     */
    public void sendDailyReport(File pdfFile, EmailCallback callback) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                
                String senderEmail = prefs.getString(KEY_SENDER_EMAIL, "");
                String senderPassword = prefs.getString(KEY_SENDER_PASSWORD, "");
                String recipientEmail = prefs.getString(KEY_RECIPIENT_EMAIL, "");
                String smtpHost = prefs.getString(KEY_SMTP_HOST, "smtp.gmail.com");
                String smtpPort = prefs.getString(KEY_SMTP_PORT, "587");

                if (senderEmail.isEmpty() || senderPassword.isEmpty() || recipientEmail.isEmpty()) {
                    Log.e(TAG, "Email configuration is incomplete");
                    callback.onError("Email configuration is incomplete. Please configure email settings.");
                    return;
                }

                Log.d(TAG, "Attempting to send email from: " + senderEmail + " to: " + recipientEmail);
                Log.d(TAG, "SMTP: " + smtpHost + ":" + smtpPort);

                // Setup mail server properties
                Properties props = new Properties();
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.host", smtpHost);
                props.put("mail.smtp.port", smtpPort);
                props.put("mail.smtp.ssl.trust", smtpHost);
                props.put("mail.smtp.ssl.protocols", "TLSv1.2");
                props.put("mail.debug", "true");  // Enable debug logging

                Log.d(TAG, "Creating mail session...");

                // Create session with authentication
                Session session = Session.getInstance(props, new javax.mail.Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        Log.d(TAG, "Authenticating...");
                        return new PasswordAuthentication(senderEmail, senderPassword);
                    }
                });

                Log.d(TAG, "Creating message...");

                // Create message
                Message message = new MimeMessage(session);
                message.setFrom(new InternetAddress(senderEmail));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail));
                message.setSubject("Daily Posture Analytics Report - " + 
                    new java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                        .format(new java.util.Date()));

                Log.d(TAG, "Creating email body...");

                // Create email body
                BodyPart messageBodyPart = new MimeBodyPart();
                String emailBody = "Hello,\n\n" +
                        "Please find attached your daily posture analytics report.\n\n" +
                        "This report contains:\n" +
                        "- Summary of posture statistics\n" +
                        "- Good posture, slouching, and cross-legged percentages\n" +
                        "- Detailed metrics and visualizations\n\n" +
                        "Best regards,\n" +
                        "Posture Analyzer App";
                messageBodyPart.setText(emailBody);

                // Create multipart message
                Multipart multipart = new MimeMultipart();
                multipart.addBodyPart(messageBodyPart);

                // Attach PDF file
                if (pdfFile != null && pdfFile.exists()) {
                    Log.d(TAG, "Attaching PDF: " + pdfFile.getName() + " (" + pdfFile.length() + " bytes)");
                    messageBodyPart = new MimeBodyPart();
                    DataSource source = new FileDataSource(pdfFile);
                    messageBodyPart.setDataHandler(new DataHandler(source));
                    messageBodyPart.setFileName(pdfFile.getName());
                    multipart.addBodyPart(messageBodyPart);
                } else {
                    Log.w(TAG, "No PDF file to attach");
                }

                // Set content
                message.setContent(multipart);

                Log.d(TAG, "Sending email via SMTP...");

                // Send email
                Transport.send(message);
                Log.d(TAG, "Email sent successfully to: " + recipientEmail);
                callback.onEmailSent();

            } catch (MessagingException e) {
                Log.e(TAG, "MessagingException sending email", e);
                String errorDetail = e.getMessage();
                if (e.getCause() != null) {
                    errorDetail += "\nCause: " + e.getCause().getMessage();
                }
                callback.onError("Failed to send email: " + errorDetail);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error sending email", e);
                callback.onError("Unexpected error: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Save email configuration
     */
    public void saveEmailConfig(String senderEmail, String senderPassword, 
                                String recipientEmail, String smtpHost, String smtpPort) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_SENDER_EMAIL, senderEmail);
        editor.putString(KEY_SENDER_PASSWORD, senderPassword);
        editor.putString(KEY_RECIPIENT_EMAIL, recipientEmail);
        editor.putString(KEY_SMTP_HOST, smtpHost);
        editor.putString(KEY_SMTP_PORT, smtpPort);
        editor.apply();
        Log.d(TAG, "Email configuration saved");
    }

    /**
     * Enable or disable daily email reports
     */
    public void setEmailEnabled(boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(KEY_EMAIL_ENABLED, enabled);
        editor.apply();
        Log.d(TAG, "Email reports " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Check if email reports are enabled
     */
    public boolean isEmailEnabled() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_EMAIL_ENABLED, false);
    }

    /**
     * Check if email is configured
     */
    public boolean isEmailConfigured() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String senderEmail = prefs.getString(KEY_SENDER_EMAIL, "");
        String senderPassword = prefs.getString(KEY_SENDER_PASSWORD, "");
        String recipientEmail = prefs.getString(KEY_RECIPIENT_EMAIL, "");
        return !senderEmail.isEmpty() && !senderPassword.isEmpty() && !recipientEmail.isEmpty();
    }

    /**
     * Get current email configuration
     */
    public EmailConfig getEmailConfig() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        EmailConfig config = new EmailConfig();
        config.senderEmail = prefs.getString(KEY_SENDER_EMAIL, "");
        config.recipientEmail = prefs.getString(KEY_RECIPIENT_EMAIL, "");
        config.smtpHost = prefs.getString(KEY_SMTP_HOST, "smtp.gmail.com");
        config.smtpPort = prefs.getString(KEY_SMTP_PORT, "587");
        return config;
    }

    public static class EmailConfig {
        public String senderEmail;
        public String recipientEmail;
        public String smtpHost;
        public String smtpPort;
    }
}
