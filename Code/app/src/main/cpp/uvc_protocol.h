#ifndef UVC_PROTOCOL_H
#define UVC_PROTOCOL_H

#include <stdint.h>

// UVC Interface Classes
#define UVC_INTERFACE_CLASS     0x0E
#define UVC_INTERFACE_SUBCLASS_CONTROL  0x01
#define UVC_INTERFACE_SUBCLASS_STREAMING 0x02

// UVC Request Codes
#define UVC_SET_CUR  0x01
#define UVC_GET_CUR  0x81
#define UVC_GET_MIN  0x82
#define UVC_GET_MAX  0x83
#define UVC_GET_RES  0x84
#define UVC_GET_LEN  0x85
#define UVC_GET_INFO 0x86
#define UVC_GET_DEF  0x87

// Video Control Selectors
#define UVC_VC_VIDEO_POWER_MODE_CONTROL 0x01
#define UVC_VC_REQUEST_ERROR_CODE_CONTROL 0x02

// Video Streaming Interface Control Selectors
#define UVC_VS_PROBE_CONTROL  0x01
#define UVC_VS_COMMIT_CONTROL 0x02

// UVC Probe/Commit Control structure
struct uvc_streaming_control {
    uint16_t bmHint;
    uint8_t  bFormatIndex;
    uint8_t  bFrameIndex;
    uint32_t dwFrameInterval;
    uint16_t wKeyFrameRate;
    uint16_t wPFrameRate;
    uint16_t wCompQuality;
    uint16_t wCompWindowSize;
    uint16_t wDelay;
    uint32_t dwMaxVideoFrameSize;
    uint32_t dwMaxPayloadTransferSize;
    uint32_t dwClockFrequency;
    uint8_t  bmFramingInfo;
    uint8_t  bPreferedVersion;
    uint8_t  bMinVersion;
    uint8_t  bMaxVersion;
} __attribute__((__packed__));

#endif // UVC_PROTOCOL_H
