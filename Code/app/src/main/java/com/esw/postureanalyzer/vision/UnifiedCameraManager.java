package com.esw.postureanalyzer.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Surface;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;

/**
 * Unified Camera Manager that supports both internal cameras (CameraX) and USB cameras (UVC)
 * This allows seamless switching between camera types
 */
public class UnifiedCameraManager {
    
    public enum CameraType {
        INTERNAL,  // Use Android CameraX API (built-in cameras)
        USB_UVC    // Use USB Video Class cameras
    }
    
    private final AppCompatActivity activity;
    private final FrameListener frameListener;
    
    private CameraXManager cameraXManager;
    private UVCCameraManager uvcCameraManager;
    private CameraType currentCameraType;
    private boolean isStarted = false;

    public interface FrameListener {
        void onFrame(Bitmap bitmap, int rotationDegrees);
    }
    
    public interface CameraStatusListener {
        void onCameraStarted(CameraType type);
        void onCameraStopped(CameraType type);
        void onError(String message);
    }
    
    private CameraStatusListener statusListener;

    public UnifiedCameraManager(AppCompatActivity activity, FrameListener frameListener) {
        this.activity = activity;
        this.frameListener = frameListener;
        this.currentCameraType = CameraType.INTERNAL; // Default to internal camera
    }
    
    public void setStatusListener(CameraStatusListener listener) {
        this.statusListener = listener;
    }

    /**
     * Start camera with internal CameraX
     */
    public void startInternalCamera(PreviewView previewView) {
        stopCamera(); // Stop any existing camera
        
        currentCameraType = CameraType.INTERNAL;
        
        if (cameraXManager == null) {
            cameraXManager = new CameraXManager(activity, previewView, 
                (bitmap, rotation) -> frameListener.onFrame(bitmap, rotation));
        }
        
        cameraXManager.startCamera();
        isStarted = true;
        
        if (statusListener != null) {
            statusListener.onCameraStarted(CameraType.INTERNAL);
        }
    }

    /**
     * Start USB UVC camera
     */
    public void startUSBCamera(Surface previewSurface) {
        startUSBCamera(previewSurface, null);
    }
    
    /**
     * Start USB UVC camera with ImageView for display
     */
    public void startUSBCamera(Surface previewSurface, ImageView usbPreviewView) {
        stopCamera(); // Stop any existing camera
        
        currentCameraType = CameraType.USB_UVC;
        
        if (uvcCameraManager == null) {
            uvcCameraManager = new UVCCameraManager(activity, 
                (bitmap, rotation) -> frameListener.onFrame(bitmap, rotation));
            
            uvcCameraManager.setConnectionListener(new UVCCameraManager.ConnectionListener() {
                @Override
                public void onCameraConnected() {
                    if (statusListener != null) {
                        statusListener.onCameraStarted(CameraType.USB_UVC);
                    }
                }

                @Override
                public void onCameraDisconnected() {
                    if (statusListener != null) {
                        statusListener.onCameraStopped(CameraType.USB_UVC);
                    }
                }

                @Override
                public void onError(String message) {
                    if (statusListener != null) {
                        statusListener.onError(message);
                    }
                }
            });
            
            uvcCameraManager.initialize();
        }
        
        // Set the USB preview ImageView for displaying USB camera frames
        if (usbPreviewView != null) {
            uvcCameraManager.setPreviewView(usbPreviewView);
        }
        
        uvcCameraManager.startCamera(previewSurface);
        isStarted = true;
    }

    /**
     * Stop the current camera
     */
    public void stopCamera() {
        if (!isStarted) {
            return;
        }
        
        if (currentCameraType == CameraType.USB_UVC && uvcCameraManager != null) {
            uvcCameraManager.stopCamera();
        }
        
        // CRITICAL: Explicitly stop CameraX to prevent both cameras running simultaneously
        if (cameraXManager != null) {
            cameraXManager.stopCamera();
        }
        
        isStarted = false;
        
        if (statusListener != null) {
            statusListener.onCameraStopped(currentCameraType);
        }
    }

    /**
     * Release all camera resources
     */
    public void release() {
        if (uvcCameraManager != null) {
            uvcCameraManager.release();
            uvcCameraManager = null;
        }
        
        // CameraX manager doesn't need explicit release (lifecycle-aware)
        cameraXManager = null;
        
        isStarted = false;
    }

    /**
     * Get current camera type
     */
    public CameraType getCurrentCameraType() {
        return currentCameraType;
    }

    /**
     * Check if camera is currently running
     */
    public boolean isStreaming() {
        if (currentCameraType == CameraType.USB_UVC && uvcCameraManager != null) {
            return uvcCameraManager.isStreaming();
        }
        return isStarted && currentCameraType == CameraType.INTERNAL;
    }

    /**
     * Switch between internal and USB camera
     */
    public void switchCamera(CameraType newType, Object previewObject) {
        if (newType == currentCameraType) {
            return; // Already using this camera type
        }
        
        if (newType == CameraType.INTERNAL && previewObject instanceof PreviewView) {
            startInternalCamera((PreviewView) previewObject);
        } else if (newType == CameraType.USB_UVC) {
            // USB camera accepts ImageView for display
            ImageView imageView = (previewObject instanceof ImageView) ? (ImageView) previewObject : null;
            Surface surface = (previewObject instanceof Surface) ? (Surface) previewObject : null;
            startUSBCamera(surface, imageView);
        } else {
            if (statusListener != null) {
                statusListener.onError("Invalid camera type or preview object");
            }
        }
    }
}
