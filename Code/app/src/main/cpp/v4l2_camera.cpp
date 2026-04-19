#include "v4l2_camera.h"
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "V4L2Camera"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

V4L2Camera::V4L2Camera() 
    : fd_(-1), buffers_(nullptr), buffer_start_(nullptr), 
      buffer_count_(0), streaming_(false) {
    memset(&current_buffer_, 0, sizeof(current_buffer_));
}

V4L2Camera::~V4L2Camera() {
    close();
}

bool V4L2Camera::open(const char* device_path) {
    LOGI("Opening camera device: %s", device_path);
    
    // Use O_RDWR for read/write, O_NONBLOCK for non-blocking, O_EXCL for exclusive access
    fd_ = ::open(device_path, O_RDWR | O_NONBLOCK);
    if (fd_ < 0) {
        LOGE("Failed to open device %s: %s (errno=%d)", device_path, strerror(errno), errno);
        return false;
    }
    
    LOGI("Device opened with fd=%d", fd_);
    
    if (!queryCapabilities()) {
        ::close(fd_);
        fd_ = -1;
        return false;
    }
    
    LOGI("Camera device opened successfully");
    return true;
}

bool V4L2Camera::openByFd(int fd) {
    LOGI("Opening camera by file descriptor: %d", fd);
    
    if (fd < 0) {
        LOGE("Invalid file descriptor: %d", fd);
        return false;
    }
    
    fd_ = fd;
    
    if (!queryCapabilities()) {
        fd_ = -1;
        return false;
    }
    
    LOGI("Camera opened successfully via file descriptor");
    return true;
}

void V4L2Camera::close() {
    LOGI("Closing camera (fd=%d, streaming=%d)", fd_, streaming_);
    
    if (streaming_) {
        stopStreaming();
    }
    
    freeBuffers();
    
    if (fd_ >= 0) {
        LOGI("Closing file descriptor %d", fd_);
        ::close(fd_);
        fd_ = -1;
    }
}

bool V4L2Camera::queryCapabilities() {
    struct v4l2_capability cap;
    memset(&cap, 0, sizeof(cap));
    
    if (ioctl(fd_, VIDIOC_QUERYCAP, &cap) < 0) {
        LOGE("Failed to query capabilities: %s", strerror(errno));
        return false;
    }
    
    LOGI("Driver: %s", cap.driver);
    LOGI("Card: %s", cap.card);
    LOGI("Bus info: %s", cap.bus_info);
    
    if (!(cap.capabilities & V4L2_CAP_VIDEO_CAPTURE)) {
        LOGE("Device does not support video capture");
        return false;
    }
    
    if (!(cap.capabilities & V4L2_CAP_STREAMING)) {
        LOGE("Device does not support streaming");
        return false;
    }
    
    return true;
}

bool V4L2Camera::setFormat(int width, int height, int pixelFormat) {
    struct v4l2_format fmt;
    memset(&fmt, 0, sizeof(fmt));
    
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    fmt.fmt.pix.width = width;
    fmt.fmt.pix.height = height;
    fmt.fmt.pix.pixelformat = pixelFormat;
    fmt.fmt.pix.field = V4L2_FIELD_NONE;
    
    LOGI("Attempting to set format: %dx%d, fourcc=0x%08x", width, height, pixelFormat);
    
    if (ioctl(fd_, VIDIOC_S_FMT, &fmt) < 0) {
        LOGE("Failed to set format %dx%d fourcc=0x%08x: %s (errno=%d)", 
             width, height, pixelFormat, strerror(errno), errno);
        return false;
    }
    
    LOGI("Format successfully set to %dx%d, fourcc=0x%08x", 
         fmt.fmt.pix.width, fmt.fmt.pix.height, fmt.fmt.pix.pixelformat);
    return true;
}

bool V4L2Camera::initBuffers() {
    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof(req));
    
    req.count = 4;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;
    
    if (ioctl(fd_, VIDIOC_REQBUFS, &req) < 0) {
        LOGE("Failed to request buffers: %s", strerror(errno));
        return false;
    }
    
    if (req.count < 2) {
        LOGE("Insufficient buffer memory");
        return false;
    }
    
    buffer_count_ = req.count;
    buffers_ = new v4l2_buffer[buffer_count_];
    buffer_start_ = new void*[buffer_count_];
    
    for (int i = 0; i < buffer_count_; ++i) {
        struct v4l2_buffer buf;
        memset(&buf, 0, sizeof(buf));
        
        buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;
        
        if (ioctl(fd_, VIDIOC_QUERYBUF, &buf) < 0) {
            LOGE("Failed to query buffer: %s", strerror(errno));
            freeBuffers();
            return false;
        }
        
        buffer_start_[i] = mmap(nullptr, buf.length, 
                                PROT_READ | PROT_WRITE, 
                                MAP_SHARED, 
                                fd_, buf.m.offset);
        
        if (buffer_start_[i] == MAP_FAILED) {
            LOGE("Failed to mmap buffer: %s", strerror(errno));
            freeBuffers();
            return false;
        }
        
        buffers_[i] = buf;
    }
    
    LOGI("Initialized %d buffers", buffer_count_);
    return true;
}

void V4L2Camera::freeBuffers() {
    if (buffer_start_) {
        for (int i = 0; i < buffer_count_; ++i) {
            if (buffer_start_[i] && buffer_start_[i] != MAP_FAILED) {
                munmap(buffer_start_[i], buffers_[i].length);
            }
        }
        delete[] buffer_start_;
        buffer_start_ = nullptr;
    }
    
    if (buffers_) {
        delete[] buffers_;
        buffers_ = nullptr;
    }
    
    buffer_count_ = 0;
}

bool V4L2Camera::startStreaming() {
    if (!initBuffers()) {
        return false;
    }
    
    // Queue all buffers
    for (int i = 0; i < buffer_count_; ++i) {
        struct v4l2_buffer buf;
        memset(&buf, 0, sizeof(buf));
        
        buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;
        
        if (ioctl(fd_, VIDIOC_QBUF, &buf) < 0) {
            LOGE("Failed to queue buffer: %s", strerror(errno));
            return false;
        }
    }
    
    // Start streaming
    enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (ioctl(fd_, VIDIOC_STREAMON, &type) < 0) {
        LOGE("Failed to start streaming: %s", strerror(errno));
        return false;
    }
    
    streaming_ = true;
    LOGI("Streaming started");
    return true;
}

bool V4L2Camera::stopStreaming() {
    if (!streaming_) {
        return true;
    }
    
    enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (ioctl(fd_, VIDIOC_STREAMOFF, &type) < 0) {
        LOGE("Failed to stop streaming: %s", strerror(errno));
        return false;
    }
    
    streaming_ = false;
    LOGI("Streaming stopped");
    return true;
}

bool V4L2Camera::getFrame(unsigned char** buffer, int* buffer_size) {
    if (!streaming_) {
        LOGE("Camera is not streaming");
        return false;
    }
    
    memset(&current_buffer_, 0, sizeof(current_buffer_));
    current_buffer_.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    current_buffer_.memory = V4L2_MEMORY_MMAP;
    
    if (ioctl(fd_, VIDIOC_DQBUF, &current_buffer_) < 0) {
        if (errno == EAGAIN) {
            return false; // No frame available yet
        }
        LOGE("Failed to dequeue buffer: %s", strerror(errno));
        return false;
    }
    
    *buffer = (unsigned char*)buffer_start_[current_buffer_.index];
    *buffer_size = current_buffer_.bytesused;
    
    return true;
}

void V4L2Camera::releaseFrame() {
    if (ioctl(fd_, VIDIOC_QBUF, &current_buffer_) < 0) {
        LOGE("Failed to requeue buffer: %s", strerror(errno));
    }
}
