#ifndef V4L2_CAMERA_H
#define V4L2_CAMERA_H

#include <linux/videodev2.h>
#include <string>

class V4L2Camera {
public:
    V4L2Camera();
    ~V4L2Camera();

    // Open camera device by path
    bool open(const char* device_path);
    
    // Open camera device by file descriptor (from USB Host API)
    bool openByFd(int fd);
    
    // Close camera device
    void close();
    
    // Set camera format
    bool setFormat(int width, int height, int pixelFormat);
    
    // Start streaming
    bool startStreaming();
    
    // Stop streaming
    bool stopStreaming();
    
    // Get next frame
    bool getFrame(unsigned char** buffer, int* buffer_size);
    
    // Release frame buffer
    void releaseFrame();
    
    // Check if camera is open
    bool isOpen() const { return fd_ >= 0; }

private:
    int fd_;
    struct v4l2_buffer current_buffer_;
    struct v4l2_buffer* buffers_;
    void** buffer_start_;
    int buffer_count_;
    bool streaming_;
    
    // Helper methods
    bool initBuffers();
    void freeBuffers();
    bool queryCapabilities();
};

#endif // V4L2_CAMERA_H
