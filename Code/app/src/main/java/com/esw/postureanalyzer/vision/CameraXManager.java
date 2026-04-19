package com.esw.postureanalyzer.vision;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Size;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraXManager {
    private final AppCompatActivity activity;
    private final PreviewView previewView;
    private final FrameListener listener;
    private final ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider; // Store for explicit unbinding

    public interface FrameListener {
        void onFrame(Bitmap bitmap, int rotationDegrees);
    }

    public CameraXManager(AppCompatActivity activity, PreviewView previewView, FrameListener listener) {
        this.activity = activity;
        this.previewView = previewView;
        this.listener = listener;
        this.cameraExecutor = Executors.newSingleThreadExecutor();
    }

    public void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(activity);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get(); // Store reference
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))  // 720p for better quality
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    Bitmap bitmap = image.toBitmap();
                    int rotation = image.getImageInfo().getRotationDegrees();
                    if (bitmap != null) {
                        listener.onFrame(bitmap, rotation);
                    }
                    image.close();
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(activity, cameraSelector, preview, imageAnalysis);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(activity));
    }
    
    /**
     * Stop the CameraX camera explicitly
     */
    public void stopCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
    }
}