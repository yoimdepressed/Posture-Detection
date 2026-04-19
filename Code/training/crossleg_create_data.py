import cv2
import mediapipe as mp
import numpy as np
import pandas as pd
import os

# ===== CONFIG =====
CSV_FILE = "crosslegged_sitting_data.csv"
LABEL = int(input("Enter label (1 = cross-legged, 0 = normal sitting): "))

# Mediapipe setup
mp_pose = mp.solutions.pose
mp_drawing = mp.solutions.drawing_utils

# ===== Helper: compute angle between 3 points =====
def angle_3pts(a, b, c):
    a, b, c = np.array(a), np.array(b), np.array(c)
    ba, bc = a - b, c - b
    cosine = np.dot(ba, bc) / (np.linalg.norm(ba) * np.linalg.norm(bc) + 1e-6)
    return round(np.degrees(np.arccos(np.clip(cosine, -1.0, 1.0))), 2)

# ===== Feature extraction =====
def get_features(landmarks, w, h):
    def to_pixel(lm): return np.array([lm.x * w, lm.y * h])

    # Key joints
    left_hip = to_pixel(landmarks[mp_pose.PoseLandmark.LEFT_HIP.value])
    right_hip = to_pixel(landmarks[mp_pose.PoseLandmark.RIGHT_HIP.value])
    left_knee = to_pixel(landmarks[mp_pose.PoseLandmark.LEFT_KNEE.value])
    right_knee = to_pixel(landmarks[mp_pose.PoseLandmark.RIGHT_KNEE.value])
    left_ankle = to_pixel(landmarks[mp_pose.PoseLandmark.LEFT_ANKLE.value])
    right_ankle = to_pixel(landmarks[mp_pose.PoseLandmark.RIGHT_ANKLE.value])

    # Angles
    left_leg_angle = angle_3pts(left_hip, left_knee, left_ankle)
    right_leg_angle = angle_3pts(right_hip, right_knee, right_ankle)

    # Distances
    knee_dist = np.linalg.norm(left_knee - right_knee)
    ankle_dist = np.linalg.norm(left_ankle - right_ankle)
    hip_dist = np.linalg.norm(left_hip - right_hip) + 1e-6  # avoid div0

    # Normalize distances by hip width (removes zoom effect)
    knee_dist /= hip_dist
    ankle_dist /= hip_dist

    # Crossing flags
    ankle_cross = 1 if left_ankle[0] > right_ankle[0] else 0
    knee_cross = 1 if left_knee[0] > right_knee[0] else 0

    return [
        left_leg_angle, right_leg_angle,
        knee_dist, ankle_dist,
        ankle_cross, knee_cross
    ]

# ===== CSV Setup =====
columns = [
    'left_leg_angle', 'right_leg_angle',
    'knee_dist', 'ankle_dist',
    'ankle_cross', 'knee_cross', 'label'
]

if not os.path.exists(CSV_FILE):
    pd.DataFrame(columns=columns).to_csv(CSV_FILE, index=False)

# ===== Webcam capture =====
cap = cv2.VideoCapture(0)
with mp_pose.Pose(min_detection_confidence=0.6, min_tracking_confidence=0.6) as pose:
    print("Collecting data... Press 'q' to quit.")
    count = 0

    while cap.isOpened():
        ret, frame = cap.read()
        if not ret:
            break

        frame = cv2.flip(frame, 1)
        h, w, _ = frame.shape
        results = pose.process(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))

        if results.pose_landmarks:
            mp_drawing.draw_landmarks(frame, results.pose_landmarks, mp_pose.POSE_CONNECTIONS)
            landmarks = results.pose_landmarks.landmark
            features = get_features(landmarks, w, h)
            features.append(LABEL)

            pd.DataFrame([features], columns=columns).to_csv(CSV_FILE, mode='a', header=False, index=False)
            count += 1

        cv2.putText(frame, f"Samples: {count}", (10, 40), cv2.FONT_HERSHEY_SIMPLEX, 1, (255,255,0), 2)
        cv2.imshow("Data Collection - Sitting", frame)

        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

cap.release()
cv2.destroyAllWindows()
print(f"✅ Data collection finished. {count} samples saved to {CSV_FILE}")
