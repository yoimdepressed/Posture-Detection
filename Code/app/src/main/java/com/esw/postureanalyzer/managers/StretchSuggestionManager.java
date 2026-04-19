package com.esw.postureanalyzer.managers;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import com.esw.postureanalyzer.R;
import com.bumptech.glide.Glide;

/**
 * Manages stretch suggestions when slouching is detected
 * - Shows chest opener stretch recommendation
 * - Displays animated GIF or links to YouTube video
 */
public class StretchSuggestionManager {
    private static final String TAG = "StretchSuggestionManager";
    
    // YouTube video for chest opener stretch (example - replace with your preferred video)
    private static final String CHEST_OPENER_VIDEO_URL = "https://www.youtube.com/watch?v=RcKjVRw-LfI";
    
    // You can add a GIF to your drawable/assets folder and reference it here
    // For now, we'll use a YouTube link as fallback
    
    private final Context context;
    private AlertDialog currentDialog;

    public StretchSuggestionManager(Context context) {
        this.context = context;
    }

    /**
     * Show chest opener stretch suggestion
     */
    public void showChestOpenerStretch() {
        if (currentDialog != null && currentDialog.isShowing()) {
            return; // Don't show multiple dialogs
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_stretch_suggestion, null);
        
        TextView titleText = dialogView.findViewById(R.id.stretch_title);
        TextView descriptionText = dialogView.findViewById(R.id.stretch_description);
        ImageView stretchImage = dialogView.findViewById(R.id.stretch_image);
        Button watchVideoButton = dialogView.findViewById(R.id.watch_video_button);
        Button dismissButton = dialogView.findViewById(R.id.dismiss_button);

        titleText.setText("🧘 Try a Chest Opener Stretch");
        descriptionText.setText(
                "Slouching compresses your chest. Try this stretch:\n\n" +
                "1. Stand or sit up straight\n" +
                "2. Clasp hands behind your back\n" +
                "3. Straighten arms and lift hands slightly\n" +
                "4. Pull shoulders back and open chest\n" +
                "5. Hold for 15-30 seconds\n" +
                "6. Breathe deeply\n\n" +
                "Repeat 2-3 times throughout the day."
        );

        // Load animated GIF if you have one in drawable
        // For demonstration, we'll use a placeholder or you can add your own GIF
        try {
            // If you add a GIF to res/drawable/chest_opener.gif, uncomment:
            // Glide.with(context).asGif().load(R.drawable.chest_opener).into(stretchImage);
            
            // For now, hide the image or show a placeholder
            stretchImage.setVisibility(View.GONE);
        } catch (Exception e) {
            stretchImage.setVisibility(View.GONE);
        }

        // Watch video button
        watchVideoButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(CHEST_OPENER_VIDEO_URL));
            context.startActivity(intent);
            if (currentDialog != null) {
                currentDialog.dismiss();
            }
        });

        // Dismiss button
        dismissButton.setOnClickListener(v -> {
            if (currentDialog != null) {
                currentDialog.dismiss();
            }
        });

        builder.setView(dialogView);
        currentDialog = builder.create();
        currentDialog.show();
    }

    /**
     * Dismiss any currently showing dialog
     */
    public void dismissCurrentDialog() {
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
            currentDialog = null;
        }
    }

    /**
     * Check if a dialog is currently showing
     */
    public boolean isDialogShowing() {
        return currentDialog != null && currentDialog.isShowing();
    }
}
