#include "uvc_camera.h"
#include "uvc_protocol.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "UVCCamera"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

UVCCamera::UVCCamera() 
    : env_(nullptr), usbConnection_(nullptr), usbDevice_(nullptr),
      bulkEndpoint_(nullptr), width_(640), height_(480), streaming_(false),
      frameBuffer_(nullptr), frameBufferSize_(0) {
}

UVCCamera::~UVCCamera() {
    close();
}

bool UVCCamera::open(JNIEnv* env, jobject usbConnection, jobject usbDevice) {
    LOGI("Opening UVC camera via USB Host API");
    
    env_ = env;
    usbConnection_ = env->NewGlobalRef(usbConnection);
    usbDevice_ = env->NewGlobalRef(usbDevice);
    
    if (!findStreamingInterface()) {
        LOGE("Failed to find streaming interface");
        return false;
    }
    
    if (!findBulkEndpoint()) {
        LOGE("Failed to find bulk endpoint");
        return false;
    }
    
    LOGI("UVC camera opened successfully");
    return true;
}

void UVCCamera::close() {
    if (streaming_) {
        stopStreaming();
    }
    
    if (frameBuffer_) {
        delete[] frameBuffer_;
        frameBuffer_ = nullptr;
        frameBufferSize_ = 0;
    }
    
    if (env_) {
        if (usbConnection_) {
            env_->DeleteGlobalRef(usbConnection_);
            usbConnection_ = nullptr;
        }
        if (usbDevice_) {
            env_->DeleteGlobalRef(usbDevice_);
            usbDevice_ = nullptr;
        }
        if (bulkEndpoint_) {
            env_->DeleteGlobalRef(bulkEndpoint_);
            bulkEndpoint_ = nullptr;
        }
    }
    
    env_ = nullptr;
}

bool UVCCamera::findStreamingInterface() {
    // Get UsbDevice class
    jclass usbDeviceClass = env_->FindClass("android/hardware/usb/UsbDevice");
    if (!usbDeviceClass) {
        LOGE("Failed to find UsbDevice class");
        return false;
    }
    
    // Get interface count
    jmethodID getInterfaceCountMethod = env_->GetMethodID(usbDeviceClass, "getInterfaceCount", "()I");
    int interfaceCount = env_->CallIntMethod(usbDevice_, getInterfaceCountMethod);
    
    LOGI("Device has %d interfaces", interfaceCount);
    
    // Find Video Streaming interface
    jmethodID getInterfaceMethod = env_->GetMethodID(usbDeviceClass, "getInterface", "(I)Landroid/hardware/usb/UsbInterface;");
    
    for (int i = 0; i < interfaceCount; i++) {
        jobject usbInterface = env_->CallObjectMethod(usbDevice_, getInterfaceMethod, i);
        
        jclass usbInterfaceClass = env_->FindClass("android/hardware/usb/UsbInterface");
        jmethodID getInterfaceClassMethod = env_->GetMethodID(usbInterfaceClass, "getInterfaceClass", "()I");
        jmethodID getInterfaceSubclassMethod = env_->GetMethodID(usbInterfaceClass, "getInterfaceSubclass", "()I");
        
        int interfaceClass = env_->CallIntMethod(usbInterface, getInterfaceClassMethod);
        int interfaceSubclass = env_->CallIntMethod(usbInterface, getInterfaceSubclassMethod);
        
        LOGI("Interface %d: class=%d, subclass=%d", i, interfaceClass, interfaceSubclass);
        
        // Check if this is a Video Streaming interface
        if (interfaceClass == UVC_INTERFACE_CLASS && interfaceSubclass == UVC_INTERFACE_SUBCLASS_STREAMING) {
            LOGI("Found UVC streaming interface at index %d", i);
            
            // Claim interface
            jclass usbConnectionClass = env_->FindClass("android/hardware/usb/UsbDeviceConnection");
            jmethodID claimInterfaceMethod = env_->GetMethodID(usbConnectionClass, 
                "claimInterface", "(Landroid/hardware/usb/UsbInterface;Z)Z");
            
            jboolean claimed = env_->CallBooleanMethod(usbConnection_, claimInterfaceMethod, usbInterface, JNI_TRUE);
            
            if (claimed) {
                LOGI("Successfully claimed interface");
                env_->DeleteLocalRef(usbInterface);
                return true;
            } else {
                LOGE("Failed to claim interface");
            }
        }
        
        env_->DeleteLocalRef(usbInterface);
    }
    
    return false;
}

bool UVCCamera::findBulkEndpoint() {
    // Get UsbDevice class
    jclass usbDeviceClass = env_->FindClass("android/hardware/usb/UsbDevice");
    jmethodID getInterfaceCountMethod = env_->GetMethodID(usbDeviceClass, "getInterfaceCount", "()I");
    jmethodID getInterfaceMethod = env_->GetMethodID(usbDeviceClass, "getInterface", "(I)Landroid/hardware/usb/UsbInterface;");
    
    int interfaceCount = env_->CallIntMethod(usbDevice_, getInterfaceCountMethod);
    
    for (int i = 0; i < interfaceCount; i++) {
        jobject usbInterface = env_->CallObjectMethod(usbDevice_, getInterfaceMethod, i);
        
        jclass usbInterfaceClass = env_->FindClass("android/hardware/usb/UsbInterface");
        jmethodID getEndpointCountMethod = env_->GetMethodID(usbInterfaceClass, "getEndpointCount", "()I");
        jmethodID getEndpointMethod = env_->GetMethodID(usbInterfaceClass, "getEndpoint", "(I)Landroid/hardware/usb/UsbEndpoint;");
        
        int endpointCount = env_->CallIntMethod(usbInterface, getEndpointCountMethod);
        
        for (int j = 0; j < endpointCount; j++) {
            jobject endpoint = env_->CallObjectMethod(usbInterface, getEndpointMethod, j);
            
            jclass endpointClass = env_->FindClass("android/hardware/usb/UsbEndpoint");
            jmethodID getTypeMethod = env_->GetMethodID(endpointClass, "getType", "()I");
            jmethodID getDirectionMethod = env_->GetMethodID(endpointClass, "getDirection", "()I");
            
            int type = env_->CallIntMethod(endpoint, getTypeMethod);
            int direction = env_->CallIntMethod(endpoint, getDirectionMethod);
            
            // USB_ENDPOINT_XFER_BULK = 2, UsbConstants.USB_DIR_IN = 128
            if (type == 2 && direction == 128) {
                LOGI("Found bulk IN endpoint");
                bulkEndpoint_ = env_->NewGlobalRef(endpoint);
                env_->DeleteLocalRef(endpoint);
                env_->DeleteLocalRef(usbInterface);
                return true;
            }
            
            env_->DeleteLocalRef(endpoint);
        }
        
        env_->DeleteLocalRef(usbInterface);
    }
    
    return false;
}

bool UVCCamera::setFormat(int width, int height) {
    width_ = width;
    height_ = height;
    
    LOGI("Setting format to %dx%d", width_, height_);
    
    // Allocate frame buffer (YUYV = 2 bytes per pixel)
    frameBufferSize_ = width_ * height_ * 2;
    frameBuffer_ = new uint8_t[frameBufferSize_];
    
    return negotiateFormat();
}

bool UVCCamera::negotiateFormat() {
    LOGI("Negotiating format with camera");
    
    // For now, just return true - proper UVC negotiation would require
    // control transfers to set up streaming parameters
    // This is a simplified implementation
    
    return true;
}

bool UVCCamera::startStreaming() {
    LOGI("Starting UVC streaming");
    
    if (!bulkEndpoint_) {
        LOGE("No bulk endpoint available");
        return false;
    }
    
    streaming_ = true;
    LOGI("Streaming started");
    return true;
}

void UVCCamera::stopStreaming() {
    streaming_ = false;
    LOGI("Streaming stopped");
}

bool UVCCamera::getFrame(uint8_t** data, int* size) {
    if (!streaming_ || !frameBuffer_) {
        return false;
    }
    
    // Read frame from bulk endpoint
    int bytesRead = bulkTransfer(frameBuffer_, frameBufferSize_, 1000);
    
    if (bytesRead > 0) {
        *data = frameBuffer_;
        *size = bytesRead;
        return true;
    }
    
    return false;
}

void UVCCamera::releaseFrame() {
    // Nothing to do for now
}

int UVCCamera::bulkTransfer(uint8_t* data, int length, int timeout) {
    if (!usbConnection_ || !bulkEndpoint_) {
        return -1;
    }
    
    // Create Java byte array
    jbyteArray buffer = env_->NewByteArray(length);
    
    // Call bulkTransfer
    jclass connectionClass = env_->FindClass("android/hardware/usb/UsbDeviceConnection");
    jmethodID bulkTransferMethod = env_->GetMethodID(connectionClass, 
        "bulkTransfer", "(Landroid/hardware/usb/UsbEndpoint;[BII)I");
    
    int result = env_->CallIntMethod(usbConnection_, bulkTransferMethod, 
                                     bulkEndpoint_, buffer, length, timeout);
    
    if (result > 0) {
        // Copy data from Java array to C array
        env_->GetByteArrayRegion(buffer, 0, result, reinterpret_cast<jbyte*>(data));
    }
    
    env_->DeleteLocalRef(buffer);
    
    return result;
}
