"""
collect_auto_data.py
Collects pose-based features automatically from webcam for 3 classes:
0 = lean left
1 = lean right
2 = others (upright)

Usage:
    python collect_auto_data.py --label 0 --out dataset.csv --fps 3
"""

import argparse
import csv
import time
import cv2
import mediapipe as mp
import numpy as np

parser = argparse.ArgumentParser()
parser.add_argument("--out", default="lean_dataset.csv", help="CSV output file")
parser.add_argument("--label", type=int, choices=[0,1,2], required=True, help="Label (0:left,1:right,2:other)")
parser.add_argument("--fps", type=float, default=3.0, help="Samples per second to capture")
args = parser.parse_args()

# Mediapipe setup
mp_pose = mp.solutions.pose
pose = mp_pose.Pose(static_image_mode=False, min_detection_confidence=0.5, min_tracking_confidence=0.5)

FIELDNAMES = [
    "torso_angle", "shoulder_angle", "head_tilt_angle",
    "left_shoulder_vis", "right_shoulder_vis",
    "left_hip_vis", "right_hip_vis",
    "left_ear_vis", "right_ear_vis",
    "label"
]

def slope_angle(a, b):
    ax, ay = a
    bx, by = b
    dx, dy = bx-ax, by-ay
    if dx==0 and dy==0:
        return 0.0
    return float(np.degrees(np.arctan2(dy, dx)))

def midpoint(p, q):
    return ((p[0]+q[0])/2.0, (p[1]+q[1])/2.0)

def extract_features(landmarks, w, h):
    def lm(i):
        l = landmarks[i]
        return (l.x*w, l.y*h), l.visibility if hasattr(l, "visibility") else 1.0

    L_SH, R_SH = mp_pose.PoseLandmark.LEFT_SHOULDER.value, mp_pose.PoseLandmark.RIGHT_SHOULDER.value
    L_HIP, R_HIP = mp_pose.PoseLandmark.LEFT_HIP.value, mp_pose.PoseLandmark.RIGHT_HIP.value
    L_EAR, R_EAR = mp_pose.PoseLandmark.LEFT_EAR.value, mp_pose.PoseLandmark.RIGHT_EAR.value
    NOSE = mp_pose.PoseLandmark.NOSE.value

    (lsh, lsh_vis), (rsh, rsh_vis) = lm(L_SH), lm(R_SH)
    (lh, lh_vis), (rh, rh_vis) = lm(L_HIP), lm(R_HIP)
    (lear, lear_vis), (rear, rear_vis) = lm(L_EAR), lm(R_EAR)
    (nose, nose_vis) = lm(NOSE)

    mid_sh = midpoint(lsh, rsh)
    mid_hip = midpoint(lh, rh)

    v = np.array(mid_sh) - np.array(mid_hip)
    torso_angle = float(np.degrees(np.arctan2(-v[0], -v[1])))
    shoulder_angle = slope_angle(lsh, rsh)
    head_tilt_angle = slope_angle(lear, rear)

    return {
        "torso_angle": torso_angle,
        "shoulder_angle": shoulder_angle,
        "head_tilt_angle": head_tilt_angle,
        "left_shoulder_vis": lsh_vis,
        "right_shoulder_vis": rsh_vis,
        "left_hip_vis": lh_vis,
        "right_hip_vis": rh_vis,
        "left_ear_vis": lear_vis,
        "right_ear_vis": rear_vis
    }

# Prepare CSV
write_header = False
try:
    with open(args.out, "r") as f:
        pass
except FileNotFoundError:
    write_header = True

with open(args.out, "a", newline="") as csvfile:
    writer = csv.DictWriter(csvfile, fieldnames=FIELDNAMES)
    if write_header:
        writer.writeheader()

    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        raise SystemExit("Cannot open webcam")

    print(f"Collecting automatically at {args.fps:.1f} fps for label {args.label}")
    print("Press 'q' to stop.")

    interval = 1.0 / args.fps
    last_time = 0
    samples = 0

    while True:
        ret, frame = cap.read()
        if not ret:
            break
        img = cv2.flip(frame, 1)
        h, w = img.shape[:2]
        rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
        res = pose.process(rgb)

        if res.pose_landmarks:
            cv2.putText(img, "Pose detected", (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0,255,0), 2)
        else:
            cv2.putText(img, "No pose", (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0,0,255), 2)

        # Automatic sampling
        now = time.time()
        if res.pose_landmarks and (now - last_time) >= interval:
            feats = extract_features(res.pose_landmarks.landmark, w, h)
            feats["label"] = args.label
            writer.writerow(feats)
            csvfile.flush()
            samples += 1
            last_time = now
            cv2.putText(img, f"Captured: {samples}", (10, 60), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255,255,255), 2)

        cv2.imshow("Auto Collect", img)
        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

    print(f"Captured total {samples} samples for label {args.label}")
    cap.release()
    cv2.destroyAllWindows()
