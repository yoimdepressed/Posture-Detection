import cv2
import mediapipe as mp
import numpy as np
import pandas as pd
import os

# ===== CONFIG =====
POSTURE_NAME = input("Enter posture (0=slouching, 1=straight): ")
CSV_FILE = "pose_dataset.csv"
FRAME_INTERVAL = 5  # take one frame every 5 frames to avoid overfitting

os.makedirs("frames", exist_ok=True)

# Mediapipe setup
mp_pose = mp.solutions.pose
mp_drawing = mp.solutions.drawing_utils

# --- Angle calculation ---
def angle_3pts(a, b, c):
    a, b, c = np.array(a), np.array(b), np.array(c)
    ba, bc = a - b, c - b
    cosine_angle = np.dot(ba, bc) / (np.linalg.norm(ba) * np.linalg.norm(bc) + 1e-6)
    angle = np.degrees(np.arccos(np.clip(cosine_angle, -1.0, 1.0)))
    return round(angle, 2)

def get_upper_body_angles(landmarks, w, h):
    def to_pixel(lm):
        return [lm.x * w, lm.y * h]

    left_shoulder = to_pixel(landmarks[mp_pose.PoseLandmark.LEFT_SHOULDER.value])
    right_shoulder = to_pixel(landmarks[mp_pose.PoseLandmark.RIGHT_SHOULDER.value])
    left_hip = to_pixel(landmarks[mp_pose.PoseLandmark.LEFT_HIP.value])
    right_hip = to_pixel(landmarks[mp_pose.PoseLandmark.RIGHT_HIP.value])

    # 1. Torso tilt (torsion) angle
    torso_tilt = angle_3pts(left_shoulder, left_hip, right_shoulder)
    # 2. Left shoulder-hip-right hip angle
    left_angle = angle_3pts(left_shoulder, left_hip, right_hip)
    # 3. Right shoulder-hip-left hip angle
    right_angle = angle_3pts(right_shoulder, right_hip, left_hip)

    return [torso_tilt, left_angle, right_angle]

# --- Load or create CSV ---
if os.path.exists(CSV_FILE) and os.path.getsize(CSV_FILE) > 0:
    df = pd.read_csv(CSV_FILE)
else:
    df = pd.DataFrame(columns=['torso_tilt','left_angle','right_angle','label'])

# --- Start webcam capture ---
cap = cv2.VideoCapture(0)
frame_count = 0
print("Collecting data... Press 'q' to stop.")

with mp_pose.Pose(min_detection_confidence=0.5, min_tracking_confidence=0.5) as pose:
    while cap.isOpened():
        ret, frame = cap.read()
        if not ret:
            break

        frame_count += 1
        if frame_count % FRAME_INTERVAL != 0:
            continue  # skip frames to avoid overfitting

        h, w, _ = frame.shape
        frame = cv2.flip(frame, 1)
        results = pose.process(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))

        if results.pose_landmarks:
            mp_drawing.draw_landmarks(frame, results.pose_landmarks, mp_pose.POSE_CONNECTIONS)
            angles = get_upper_body_angles(results.pose_landmarks.landmark, w, h)
            df.loc[len(df)] = angles + [int(POSTURE_NAME)]

            # Optional: display angles
            cv2.putText(frame, f"Angles: {angles}", (10,50), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0,255,0), 2)

        cv2.imshow("Collecting Data", frame)
        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

# Save CSV
df.to_csv(CSV_FILE, index=False)
cap.release()
cv2.destroyAllWindows()
print(f"Data saved to {CSV_FILE}. Total samples: {len(df)}")
