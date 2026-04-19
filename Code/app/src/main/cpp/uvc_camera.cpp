#include <jni.h>
#include <android/log.h>
#include "v4l2_camera.h"
#include <linux/videodev2.h>

#define LOG_TAG "UVCCamera-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_esw_postureanalyzer_vision_UVCCameraManager_nativeCreate(
        JNIEnv* env, jobject thiz) {
    LOGI("Creating native V4L2 camera instance");
    V4L2Camera* camera = new V4L2Camera();
    return reinterpret_cast<jlong>(camera);
}

JNIEXPORT void JNICALL
Java_com_esw_postureanalyzer_vision_UVCCameraManager_nativeDestroy(
        JNIEnv* env, jobject thiz, jlong native_ptr) {
    LOGI("Destroying native V4L2 camera instance");
    V4L2Camera* camera = reinterpret_cast<V4L2Camera*>(native_ptr);
    if (camera) {
        delete camera;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_esw_postureanalyzer_vision_UVCCameraManager_nativeOpen(
        JNIEnv* env, jobject thiz, jlong native_ptr, jstring device_path) {
    V4L2Camera* camera = reinterpret_cast<V4L2Camera*>(native_ptr);
    if (!camera) {
        LOGE("Invalid camera pointer");
        return JNI_FALSE;
    }
    
    const char* path = env->GetStringUTFChars(device_path, nullptr);
    LOGI("Opening V4L2 camera: %s", path);
    
    bool result = camera->open(path);
    
    env->ReleaseStringUTFChars(device_path, path);
    
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_esw_postureanalyzer_vision_UVCCameraManager_nativeOpenByFd(
        JNIEnv* env, jobject thiz, jlong native_ptr, jint fd) {
    V4L2Camera* camera = reinterpret_cast<V4L2Camera*>(native_ptr);
    if (!camera) {
        LOGE("Invalid camera pointer");
        return JNI_FALSE;
    }
    
    LOGI("Opening V4L2 camera by file descriptor: %d", fd);
    bool result = camera->openByFd(fd);
    
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_esw_postureanalyzer_vision_UVCCameraManager_nativeClose(
        JNIEnv* env, jobject thiz, jlong native_ptr) {
    V4L2Camera* camera = reinterpret_cast<V4L2Camera*>(native_ptr);
    if (camera) {
        camera->close();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_esw_postureanalyzer_vision_UVCCameraManager_nativeSetFormat(
        JNIEnv* env, jobject thiz, jlong native_ptr, 
        jint width, jint height, jint pixel_format) {
    V4L2Camera* camera = reinterpret_cast<V4L2Camera*>(native_ptr);
    if (!camera) {
        LOGE("Invalid camera pointer");
        return JNI_FALSE;
    }
    
    bool result = camera->setFormat(width, height, pixel_format);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_esw_postureanalyzer_vision_UVCCameraManager_nativeStartStreaming(
        JNIEnv* env, jobject thiz, jlong native_ptr) {
    V4L2Camera* camera = reinterpret_cast<V4L2Camera*>(native_ptr);
    if (!camera) {
        LOGE("Invalid camera pointer");
        return JNI_FALSE;
    }
    
    bool result = camera->startStreaming();
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_esw_postureanalyzer_vision_UVCCameraManager_nativeStopStreaming(
        JNIEnv* env, jobject thiz, jlong native_ptr) {
    V4L2Camera* camera = reinterpret_cast<V4L2Camera*>(native_ptr);
    if (camera) {
        camera->stopStreaming();
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_esw_postureanalyzer_vision_UVCCameraManager_nativeGetFrame(
        JNIEnv* env, jobject thiz, jlong native_ptr) {
    V4L2Camera* camera = reinterpret_cast<V4L2Camera*>(native_ptr);
    if (!camera) {
        LOGE("Invalid camera pointer");
        return nullptr;
    }
    
    unsigned char* buffer = nullptr;
    int buffer_size = 0;
    
    if (!camera->getFrame(&buffer, &buffer_size)) {
        return nullptr; // No frame available
    }
    
    // Create Java byte array and copy frame data
    jbyteArray result = env->NewByteArray(buffer_size);
    if (result) {
        env->SetByteArrayRegion(result, 0, buffer_size, 
                                reinterpret_cast<jbyte*>(buffer));
    }
    
    // Release the frame
    camera->releaseFrame();
    
    return result;
}

JNIEXPORT jint JNICALL
Java_com_esw_postureanalyzer_vision_UVCCameraManager_getYUYVFormat(
        JNIEnv* env, jobject thiz) {
    return V4L2_PIX_FMT_YUYV;
}

JNIEXPORT jint JNICALL
Java_com_esw_postureanalyzer_vision_UVCCameraManager_getMJPEGFormat(
        JNIEnv* env, jobject thiz) {
    return V4L2_PIX_FMT_MJPEG;
}

} // extern "C"
