#ifndef UVC_CAMERA_H
#define UVC_CAMERA_H

#include <jni.h>
#include <string>

class UVCCamera {
public:
    UVCCamera();
    ~UVCCamera();

    // Open camera using USB file descriptor from Java
    bool open(JNIEnv* env, jobject usbConnection, jobject usbDevice);
    
    // Close camera
    void close();
    
    // Set camera format
    bool setFormat(int width, int height);
    
    // Start streaming
    bool startStreaming();
    
    // Stop streaming
    void stopStreaming();
    
    // Get next frame (blocks until frame available)
    bool getFrame(uint8_t** data, int* size);
    
    // Release frame
    void releaseFrame();
    
    // Check if streaming
    bool isStreaming() const { return streaming_; }

private:
    JNIEnv* env_;
    jobject usbConnection_;
    jobject usbDevice_;
    jobject bulkEndpoint_;
    
    int width_;
    int height_;
    bool streaming_;
    
    uint8_t* frameBuffer_;
    int frameBufferSize_;
    
    // Helper methods
    bool findStreamingInterface();
    bool findBulkEndpoint();
    bool negotiateFormat();
    bool sendProbeControl(bool commit);
    int bulkTransfer(uint8_t* data, int length, int timeout);
};

#endif // UVC_CAMERA_H
