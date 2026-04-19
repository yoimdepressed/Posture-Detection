"""
Run the exported TFLite model in real-time on webcam feed using MediaPipe pose.

Usage:
    python run_tflite_realtime.py

Requires files in same folder:
    - crosslegged.tflite
    - scaler.npz
"""

import cv2
import mediapipe as mp
import numpy as np
import tensorflow as tf
import time
import os

TFLITE_FILE = "crosslegged.tflite"
SCALER_FILE = "scaler.npz"
THRESHOLD = 0.5  # classification threshold

if not os.path.exists(TFLITE_FILE):
    raise FileNotFoundError(f"{TFLITE_FILE} not found. Run training script first.")
if not os.path.exists(SCALER_FILE):
    raise FileNotFoundError(f"{SCALER_FILE} not found. Run training script first.")

scaler = np.load(SCALER_FILE)
MEAN = scaler['mean']
STD = scaler['std']

# Mediapipe
mp_pose = mp.solutions.pose
mp_drawing = mp.solutions.drawing_utils

# TFLite interpreter
interpreter = tf.lite.Interpreter(model_path=TFLITE_FILE)
interpreter.allocate_tensors()
input_details = interpreter.get_input_details()
output_details = interpreter.get_output_details()

# Helper functions (must match training feature extraction)
def angle_3pts(a, b, c):
    a, b, c = np.array(a), np.array(b), np.array(c)
    ba, bc = a - b, c - b
    cosine = np.dot(ba, bc) / (np.linalg.norm(ba) * np.linalg.norm(bc) + 1e-6)
    return float(np.degrees(np.arccos(np.clip(cosine, -1.0, 1.0))))

def to_pixel(lm, w, h):
    return np.array([lm.x * w, lm.y * h], dtype=np.float32)

def extract_features(landmarks, w, h):
    # Must match exactly the get_features used for training
    left_hip = to_pixel(landmarks[mp_pose.PoseLandmark.LEFT_HIP.value], w, h)
    right_hip = to_pixel(landmarks[mp_pose.PoseLandmark.RIGHT_HIP.value], w, h)
    left_knee = to_pixel(landmarks[mp_pose.PoseLandmark.LEFT_KNEE.value], w, h)
    right_knee = to_pixel(landmarks[mp_pose.PoseLandmark.RIGHT_KNEE.value], w, h)
    left_ankle = to_pixel(landmarks[mp_pose.PoseLandmark.LEFT_ANKLE.value], w, h)
    right_ankle = to_pixel(landmarks[mp_pose.PoseLandmark.RIGHT_ANKLE.value], w, h)

    left_leg_angle = angle_3pts(left_hip, left_knee, left_ankle)
    right_leg_angle = angle_3pts(right_hip, right_knee, right_ankle)

    knee_dist = np.linalg.norm(left_knee - right_knee)
    ankle_dist = np.linalg.norm(left_ankle - right_ankle)
    hip_dist = np.linalg.norm(left_hip - right_hip) + 1e-6

    knee_dist /= hip_dist
    ankle_dist /= hip_dist

    ankle_cross = 1.0 if left_ankle[0] > right_ankle[0] else 0.0
    knee_cross = 1.0 if left_knee[0] > right_knee[0] else 0.0

    feat = np.array([
        left_leg_angle, right_leg_angle,
        knee_dist, ankle_dist,
        ankle_cross, knee_cross
    ], dtype=np.float32)
    return feat

# Run webcam & inference
cap = cv2.VideoCapture(0)
with mp_pose.Pose(min_detection_confidence=0.6, min_tracking_confidence=0.6) as pose:
    prev_time = time.time()
    while True:
        ret, frame = cap.read()
        if not ret:
            break
        frame = cv2.flip(frame, 1)
        h, w, _ = frame.shape

        results = pose.process(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
        label_text = "No person"
        conf_text = ""

        if results.pose_landmarks:
            mp_drawing.draw_landmarks(frame, results.pose_landmarks, mp_pose.POSE_CONNECTIONS)
            lm = results.pose_landmarks.landmark

            # Make sure required landmarks are present with reasonable visibility:
            required = [
                mp_pose.PoseLandmark.LEFT_HIP.value,
                mp_pose.PoseLandmark.RIGHT_HIP.value,
                mp_pose.PoseLandmark.LEFT_KNEE.value,
                mp_pose.PoseLandmark.RIGHT_KNEE.value,
                mp_pose.PoseLandmark.LEFT_ANKLE.value,
                mp_pose.PoseLandmark.RIGHT_ANKLE.value,
            ]
            vis_ok = True
            for idx in required:
                if lm[idx].visibility < 0.4:  # if occluded or not confident
                    vis_ok = False
                    break

            if vis_ok:
                feat = extract_features(lm, w, h)
                feat_norm = (feat - MEAN) / STD
                inp = feat_norm.reshape((1, -1)).astype(np.float32)

                # Set input tensor
                interpreter.set_tensor(input_details[0]['index'], inp)
                interpreter.invoke()
                out = interpreter.get_tensor(output_details[0]['index'])
                prob = float(out[0][0])
                is_cross = prob >= THRESHOLD

                label_text = "Cross-legged" if is_cross else "Normal sitting"
                conf_text = f"{prob:.2f}"
            else:
                label_text = "Low visibility of legs"
        # FPS
        cur_time = time.time()
        fps = 1.0 / (cur_time - prev_time + 1e-6)
        prev_time = cur_time

        # Overlay
        cv2.putText(frame, f"{label_text} {conf_text}", (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0,255,0), 2)
        cv2.putText(frame, f"FPS: {int(fps)}", (10, 60), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (200,200,0), 2)

        cv2.imshow("Cross-legged Detector (TFLite)", frame)
        key = cv2.waitKey(1) & 0xFF
        if key == ord('q'):
            break

cap.release()
cv2.destroyAllWindows()
