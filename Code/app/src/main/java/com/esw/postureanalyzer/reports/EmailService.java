package com.esw.postureanalyzer.reports;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.Authenticator;
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
 * Service for sending email reports
 */
public class EmailService {
    private static final String TAG = "EmailService";
    private static final String PREFS_NAME = "EmailReportPrefs";
    private static final String KEY_RECIPIENT_EMAIL = "recipient_email";
    private static final String KEY_SENDER_EMAIL = "sender_email";
    private static final String KEY_SENDER_PASSWORD = "sender_password";
    private static final String KEY_SMTP_HOST = "smtp_host";
    private static final String KEY_SMTP_PORT = "smtp_port";
    private static final String KEY_REPORTS_ENABLED = "reports_enabled";

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
    public void sendReport(File pdfFile, String subject, String body, EmailCallback callback) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                
                String recipientEmail = prefs.getString(KEY_RECIPIENT_EMAIL, "");
                String senderEmail = prefs.getString(KEY_SENDER_EMAIL, "");
                String senderPassword = prefs.getString(KEY_SENDER_PASSWORD, "");
                String smtpHost = prefs.getString(KEY_SMTP_HOST, "smtp.gmail.com");
                String smtpPort = prefs.getString(KEY_SMTP_PORT, "587");

                if (recipientEmail.isEmpty() || senderEmail.isEmpty() || senderPassword.isEmpty()) {
                    callback.onError("Email settings not configured. Please set up email in Settings.");
                    return;
                }

                // Configure mail session
                Properties props = new Properties();
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.host", smtpHost);
                props.put("mail.smtp.port", smtpPort);
                props.put("mail.smtp.ssl.trust", smtpHost);
                props.put("mail.smtp.ssl.protocols", "TLSv1.2");

                Session session = Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(senderEmail, senderPassword);
                    }
                });

                // Create message
                Message message = new MimeMessage(session);
                message.setFrom(new InternetAddress(senderEmail));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail));
                message.setSubject(subject);

                // Create multipart message
                Multipart multipart = new MimeMultipart();

                // Add text body
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(body);
                multipart.addBodyPart(textPart);

                // Add PDF attachment
                MimeBodyPart attachmentPart = new MimeBodyPart();
                DataSource source = new FileDataSource(pdfFile);
                attachmentPart.setDataHandler(new DataHandler(source));
                attachmentPart.setFileName(pdfFile.getName());
                multipart.addBodyPart(attachmentPart);

                message.setContent(multipart);

                // Send email
                Log.d(TAG, "Sending email to: " + recipientEmail);
                Transport.send(message);
                Log.d(TAG, "Email sent successfully");

                callback.onEmailSent();

            } catch (MessagingException e) {
                Log.e(TAG, "Error sending email", e);
                callback.onError("Failed to send email: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error", e);
                callback.onError("Unexpected error: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Save email settings
     */
    public void saveSettings(String recipientEmail, String senderEmail, String senderPassword,
                           String smtpHost, String smtpPort) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_RECIPIENT_EMAIL, recipientEmail)
                .putString(KEY_SENDER_EMAIL, senderEmail)
                .putString(KEY_SENDER_PASSWORD, senderPassword)
                .putString(KEY_SMTP_HOST, smtpHost)
                .putString(KEY_SMTP_PORT, smtpPort)
                .apply();
    }

    /**
     * Get recipient email
     */
    public String getRecipientEmail() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_RECIPIENT_EMAIL, "");
    }

    /**
     * Get sender email
     */
    public String getSenderEmail() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_SENDER_EMAIL, "");
    }

    /**
     * Get SMTP host
     */
    public String getSmtpHost() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_SMTP_HOST, "smtp.gmail.com");
    }

    /**
     * Get SMTP port
     */
    public String getSmtpPort() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_SMTP_PORT, "587");
    }

    /**
     * Enable or disable daily reports
     */
    public void setReportsEnabled(boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_REPORTS_ENABLED, enabled).apply();
    }

    /**
     * Check if daily reports are enabled
     */
    public boolean areReportsEnabled() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_REPORTS_ENABLED, false);
    }

    /**
     * Check if email is configured
     */
    public boolean isConfigured() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String recipient = prefs.getString(KEY_RECIPIENT_EMAIL, "");
        String sender = prefs.getString(KEY_SENDER_EMAIL, "");
        String password = prefs.getString(KEY_SENDER_PASSWORD, "");
        return !recipient.isEmpty() && !sender.isEmpty() && !password.isEmpty();
    }
}
