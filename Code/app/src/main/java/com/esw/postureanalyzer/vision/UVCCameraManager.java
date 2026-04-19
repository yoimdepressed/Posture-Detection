package com.esw.postureanalyzer.vision;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;

/**
 * USB Camera Manager - V4L2 Implementation for QIDK
 * Works on devices with accessible /dev/video* devices (like QIDK)
 */
public class UVCCameraManager {
    private static final String TAG = "UVCCameraManager";
    private static final String ACTION_USB_PERMISSION = "com.esw.postureanalyzer.USB_PERMISSION";
    
    // Load native library
    static {
        try {
            System.loadLibrary("uvccamera");
            Log.i(TAG, "Native library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library: " + e.getMessage());
        }
    }
    
    // Native method declarations
    private native long nativeCreate();
    private native void nativeDestroy(long nativePtr);
    private native boolean nativeOpen(long nativePtr, String devicePath);
    private native boolean nativeOpenByFd(long nativePtr, int fd);
    private native void nativeClose(long nativePtr);
    private native boolean nativeSetFormat(long nativePtr, int width, int height, int pixelFormat);
    private native boolean nativeStartStreaming(long nativePtr);
    private native void nativeStopStreaming(long nativePtr);
    private native byte[] nativeGetFrame(long nativePtr);
    private native int getYUYVFormat();
    private native int getMJPEGFormat();
    
    private final Context context;
    private final FrameListener frameListener;
    private ImageView usbPreviewView;
    
    private UsbManager usbManager;
    private UsbDevice usbCamera;
    private UsbDeviceConnection usbConnection;
    private boolean isStreaming = false;
    private long nativeCameraPtr = 0;
    
    // Track the actual resolution being used
    private int currentWidth = 640;
    private int currentHeight = 480;
    
    // Cache first successful bitmap size to enforce consistency
    private int cachedBitmapWidth = -1;
    private int cachedBitmapHeight = -1;
    
    private HandlerThread frameThread;
    private Handler frameHandler;
    private volatile boolean shouldCaptureFrames = false;

    public interface FrameListener {
        void onFrame(Bitmap bitmap, int rotationDegrees);
    }

    public interface ConnectionListener {
        void onCameraConnected();
        void onCameraDisconnected();
        void onError(String message);
    }

    private ConnectionListener connectionListener;

    public UVCCameraManager(Context context, FrameListener frameListener) {
        this.context = context;
        this.frameListener = frameListener;
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    /**
     * Set the ImageView to display USB camera frames
     */
    public void setPreviewView(ImageView usbPreviewView) {
        this.usbPreviewView = usbPreviewView;
        Log.d(TAG, "USB preview ImageView set");
    }

    /**
     * Initialize USB monitoring
     */
    public void initialize() {
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        
        // Use ContextCompat for better compatibility across Android versions
        // RECEIVER_NOT_EXPORTED: This receiver only handles internal USB events
        ContextCompat.registerReceiver(context, usbReceiver, filter, 
            ContextCompat.RECEIVER_NOT_EXPORTED);
        
        Log.d(TAG, "USB camera manager initialized");
    }

    /**
     * USB broadcast receiver
     */
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && isUVCCamera(device)) {
                    Log.d(TAG, "USB camera attached: " + device.getProductName());
                    Toast.makeText(context, "USB camera detected: " + device.getProductName(), Toast.LENGTH_SHORT).show();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && device.equals(usbCamera)) {
                    Log.d(TAG, "USB camera detached");
                    stopCamera();
                    if (connectionListener != null) {
                        connectionListener.onCameraDisconnected();
                    }
                }
            } else if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            onPermissionGranted(device);
                        }
                    } else {
                        Log.d(TAG, "Permission denied for device");
                        Toast.makeText(context, "USB camera permission denied", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    };

    /**
     * Start camera - detects and requests permission for USB cameras
     */
    public void startCamera(Surface surface) {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        
        for (UsbDevice device : deviceList.values()) {
            if (isUVCCamera(device)) {
                Log.d(TAG, "Found UVC camera: " + device.getProductName());
                usbCamera = device;
                requestPermission(device);
                return;
            }
        }
        
        Log.d(TAG, "No USB camera found");
        Toast.makeText(context, "No USB camera detected. Please plug in a USB camera.", Toast.LENGTH_LONG).show();
    }

    /**
     * Check if device is a UVC (USB Video Class) camera
     */
    private boolean isUVCCamera(UsbDevice device) {
        // UVC cameras have class 14 (0x0E) - Video
        // Or interface class 14
        int deviceClass = device.getDeviceClass();
        int deviceSubclass = device.getDeviceSubclass();
        
        // Check device class
        if (deviceClass == 14 || deviceClass == 239) { // 239 = Miscellaneous
            return true;
        }
        
        // Check interfaces
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            int interfaceClass = device.getInterface(i).getInterfaceClass();
            if (interfaceClass == 14) { // Video class
                return true;
            }
        }
        
        return false;
    }

    /**
     * Request permission to access USB device
     */
    private void requestPermission(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            onPermissionGranted(device);
        } else {
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                context, 
                0, 
                new Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            );
            usbManager.requestPermission(device, permissionIntent);
            Log.d(TAG, "Requesting USB permission...");
        }
    }

    /**
     * Called when USB permission is granted
     */
    private void onPermissionGranted(UsbDevice device) {
        Log.d(TAG, "USB permission granted for: " + device.getProductName());
        
        // Open connection to device
        usbConnection = usbManager.openDevice(device);
        if (usbConnection != null) {
            Log.d(TAG, "USB device connection opened successfully");
            
            Toast.makeText(context, 
                "✓ USB Camera Connected!\nInitializing V4L2 streaming...", 
                Toast.LENGTH_SHORT).show();
            
            // Initialize native camera
            nativeCameraPtr = nativeCreate();
            if (nativeCameraPtr == 0) {
                Log.e(TAG, "Failed to create native camera");
                Toast.makeText(context, "Failed to initialize native camera", Toast.LENGTH_SHORT).show();
                usbConnection.close();
                usbConnection = null;
                return;
            }
            
            boolean opened = false;
            String openedPath = null;
            
            // Try /dev/video* paths (permissions already fixed with chmod 666)
            String[] devicePaths = {"/dev/video2", "/dev/video3", "/dev/video1", "/dev/video0"};
            
            for (String path : devicePaths) {
                Log.d(TAG, "Trying to open: " + path);
                if (nativeOpen(nativeCameraPtr, path)) {
                    opened = true;
                    openedPath = path;
                    Log.i(TAG, "Successfully opened: " + path);
                    break;
                }
            }
            
            if (!opened) {
                Log.e(TAG, "Failed to open any /dev/video* device");
                Log.e(TAG, "Checked: /dev/video2, /dev/video3, /dev/video1, /dev/video0");
                
                // Check current permissions
                try {
                    Process p = Runtime.getRuntime().exec("ls -l /dev/video2");
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                    String perms = reader.readLine();
                    Log.e(TAG, "Permissions on /dev/video2: " + perms);
                } catch (Exception e) {
                    Log.e(TAG, "Could not check permissions: " + e.getMessage());
                }
                
                Toast.makeText(context, 
                    "Cannot access /dev/video* devices\n\n" +
                    "Permissions already set (chmod 666)\n" +
                    "Check logcat for V4L2Camera errors", 
                    Toast.LENGTH_LONG).show();
                nativeDestroy(nativeCameraPtr);
                nativeCameraPtr = 0;
                usbConnection.close();
                usbConnection = null;
                return;
            }
            
            // Try different formats and resolutions (ordered by preference - stable resolution first)
            boolean formatSet = false;
            
            // Try 640x480 first - most stable across USB cameras, prevents alternating resolution bug
            int[][] resolutions = {{640, 480}, {800, 600}, {1280, 720}, {1920, 1080}, {320, 240}};
            
            for (int[] res : resolutions) {
                Log.d(TAG, "Trying MJPEG " + res[0] + "x" + res[1]);
                if (nativeSetFormat(nativeCameraPtr, res[0], res[1], getMJPEGFormat())) {
                    formatSet = true;
                    currentWidth = res[0];
                    currentHeight = res[1];
                    Log.i(TAG, "Successfully set MJPEG " + res[0] + "x" + res[1]);
                    break;
                }
            }
            
            // Fallback to YUYV
            if (!formatSet) {
                for (int[] res : resolutions) {
                    Log.d(TAG, "Trying YUYV " + res[0] + "x" + res[1]);
                    if (nativeSetFormat(nativeCameraPtr, res[0], res[1], getYUYVFormat())) {
                        formatSet = true;
                        currentWidth = res[0];
                        currentHeight = res[1];
                        Log.i(TAG, "Successfully set YUYV " + res[0] + "x" + res[1]);
                        break;
                    }
                }
            }
            
            if (!formatSet) {
                Log.e(TAG, "Failed to set any format!");
                Toast.makeText(context, "Camera doesn't support any known format", Toast.LENGTH_LONG).show();
                nativeClose(nativeCameraPtr);
                nativeDestroy(nativeCameraPtr);
                nativeCameraPtr = 0;
                usbConnection.close();
                usbConnection = null;
                return;
            }
            
            // Start streaming
            if (!nativeStartStreaming(nativeCameraPtr)) {
                Log.e(TAG, "Failed to start streaming");
                Toast.makeText(context, "Failed to start streaming", Toast.LENGTH_SHORT).show();
                nativeClose(nativeCameraPtr);
                nativeDestroy(nativeCameraPtr);
                nativeCameraPtr = 0;
                usbConnection.close();
                usbConnection = null;
                return;
            }
            
            // Start frame capture thread
            frameThread = new HandlerThread("UVC-FrameThread");
            frameThread.start();
            frameHandler = new Handler(frameThread.getLooper());
            shouldCaptureFrames = true;
            
            // Start capturing frames
            frameHandler.post(frameCaptureRunnable);
            
            isStreaming = true;
            
            if (connectionListener != null) {
                connectionListener.onCameraConnected();
            }
            
            Toast.makeText(context, 
                "✓ USB Camera Streaming!\n" + openedPath + " @ " + currentWidth + "x" + currentHeight, 
                Toast.LENGTH_SHORT).show();
            
            Log.i(TAG, "V4L2 streaming started successfully from " + openedPath);
            
        } else {
            Log.e(TAG, "Failed to open USB device connection");
            Toast.makeText(context, "Failed to connect to USB camera", Toast.LENGTH_SHORT).show();
            
            if (connectionListener != null) {
                connectionListener.onError("Failed to open device");
            }
        }
    }
    
    // Frame timing control - don't skip frames, just throttle
    private long lastFrameTime = 0;
    private static final long MIN_FRAME_INTERVAL_MS = 33; // 30 FPS max
    
    /**
     * Runnable for capturing frames from native camera
     */
    private final Runnable frameCaptureRunnable = new Runnable() {
        @Override
        public void run() {
            if (!shouldCaptureFrames || nativeCameraPtr == 0) {
                return;
            }
            
            long currentTime = System.currentTimeMillis();
            
            // Throttle frame rate to prevent overwhelming MediaPipe
            if (currentTime - lastFrameTime < MIN_FRAME_INTERVAL_MS) {
                // Schedule next attempt
                if (shouldCaptureFrames) {
                    long delay = MIN_FRAME_INTERVAL_MS - (currentTime - lastFrameTime);
                    frameHandler.postDelayed(this, delay);
                }
                return;
            }
            
            lastFrameTime = currentTime;
            
            // Get frame from native code
            byte[] frameData = nativeGetFrame(nativeCameraPtr);
            
            if (frameData != null && frameData.length > 0) {
                Bitmap bitmap = null;
                
                // Check if it's MJPEG (starts with JPEG magic bytes FF D8)
                if (frameData.length > 2 && frameData[0] == (byte)0xFF && frameData[1] == (byte)0xD8) {
                    // It's MJPEG - decode directly
                    bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.length);
                } else {
                    // It's raw format (YUYV) - convert it using configured resolution
                    bitmap = convertYUYVToBitmap(frameData, currentWidth, currentHeight);
                }
                
                // CRITICAL FIX: Enforce STRICT dimension consistency
                if (bitmap != null) {
                    // Cache the first successful bitmap size
                    if (cachedBitmapWidth == -1) {
                        cachedBitmapWidth = bitmap.getWidth();
                        cachedBitmapHeight = bitmap.getHeight();
                        Log.i(TAG, "✓ Locked bitmap size: " + cachedBitmapWidth + "x" + cachedBitmapHeight);
                    }
                    
                    // Force ALL frames to match the cached size - NO EXCEPTIONS
                    if (bitmap.getWidth() != cachedBitmapWidth || bitmap.getHeight() != cachedBitmapHeight) {
                        Log.w(TAG, "⚠ Frame size mismatch! Got " + bitmap.getWidth() + "x" + bitmap.getHeight() + ", forcing to " + cachedBitmapWidth + "x" + cachedBitmapHeight);
                        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, cachedBitmapWidth, cachedBitmapHeight, true);
                        bitmap.recycle();
                        bitmap = scaledBitmap;
                    }
                    
                    // Display on PreviewView if available
                    displayBitmapOnPreview(bitmap);
                    
                    // Send to MediaPipe for pose detection (non-blocking)
                    if (frameListener != null) {
                        frameListener.onFrame(bitmap, 0);
                    }
                }
            }
            
            // Schedule next frame capture
            if (shouldCaptureFrames) {
                frameHandler.postDelayed(this, MIN_FRAME_INTERVAL_MS);
            }
        }
    };
    
    /**
     * Convert YUYV frame data to Bitmap
     */
    private Bitmap convertYUYVToBitmap(byte[] yuyv, int width, int height) {
        try {
            // Convert YUYV to YUV420 (NV21)
            byte[] yuv420 = new byte[width * height * 3 / 2];
            
            // Simple YUYV to NV21 conversion
            for (int i = 0, j = 0; i < yuyv.length && j < width * height; i += 4, j += 2) {
                // Extract Y values
                if (j < width * height) yuv420[j] = yuyv[i];
                if (j + 1 < width * height) yuv420[j + 1] = yuyv[i + 2];
            }
            
            // Convert to Bitmap using YuvImage
            YuvImage yuvImage = new YuvImage(yuv420, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 80, out);
            byte[] jpegData = out.toByteArray();
            
            return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        } catch (Exception e) {
            Log.e(TAG, "Error converting frame: " + e.getMessage());
            return null;
        }
    }

    /**
     * Display bitmap on ImageView
     */
    private void displayBitmapOnPreview(final Bitmap bitmap) {
        if (usbPreviewView == null || bitmap == null) {
            return;
        }
        
        // Run on UI thread to update the view
        usbPreviewView.post(() -> {
            try {
                usbPreviewView.setImageBitmap(bitmap);
                // Make sure the ImageView is visible
                if (usbPreviewView.getVisibility() != android.view.View.VISIBLE) {
                    usbPreviewView.setVisibility(android.view.View.VISIBLE);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error displaying frame on preview: " + e.getMessage());
            }
        });
    }

    /**
     * Stop camera
     */
    public void stopCamera() {
        Log.d(TAG, "Stopping camera...");
        
        // Stop frame capture
        shouldCaptureFrames = false;
        
        if (frameHandler != null) {
            frameHandler.removeCallbacks(frameCaptureRunnable);
        }
        
        if (frameThread != null) {
            frameThread.quitSafely();
            try {
                frameThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping frame thread", e);
            }
            frameThread = null;
            frameHandler = null;
        }
        
        // Stop native streaming
        if (nativeCameraPtr != 0) {
            nativeStopStreaming(nativeCameraPtr);
            nativeClose(nativeCameraPtr);
            nativeDestroy(nativeCameraPtr);
            nativeCameraPtr = 0;
        }
        
        // Close USB connection
        if (usbConnection != null) {
            usbConnection.close();
            usbConnection = null;
        }
        
        isStreaming = false;
        Log.d(TAG, "Camera stopped");
    }

    /**
     * Release resources
     */
    public void release() {
        stopCamera();
        
        try {
            if (usbReceiver != null) {
                context.unregisterReceiver(usbReceiver);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver", e);
        }
        
        usbCamera = null;
        usbManager = null;
    }

    /**
     * Check if camera is streaming
     */
    public boolean isStreaming() {
        return isStreaming;
    }

    /**
     * Get camera name
     */
    public String getCameraName() {
        if (usbCamera != null && usbCamera.getProductName() != null) {
            return usbCamera.getProductName();
        }
        return "USB Camera";
    }
}
