import cv2
import mediapipe as mp
import numpy as np
import tensorflow as tf

# ===== CONFIG =====
MODEL_FILE = "lean_direction_model.tflite"
CLASS_NAMES = ["Left", "Right", "Other"]

# ===== LOAD TFLITE MODEL =====
interpreter = tf.lite.Interpreter(model_path=MODEL_FILE)
interpreter.allocate_tensors()
input_details = interpreter.get_input_details()
output_details = interpreter.get_output_details()

# ===== MEDIAPIPE SETUP =====
mp_pose = mp.solutions.pose
pose = mp_pose.Pose(static_image_mode=False, min_detection_confidence=0.5, min_tracking_confidence=0.5)

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

    return np.array([
        torso_angle, shoulder_angle, head_tilt_angle,
        lsh_vis, rsh_vis, lh_vis, rh_vis, lear_vis, rear_vis
    ], dtype=np.float32).reshape(1, -1)

# ===== WEBCAM LOOP =====
cap = cv2.VideoCapture(0)
if not cap.isOpened():
    raise SystemExit("Cannot open webcam")

print("Press 'q' to quit")

while True:
    ret, frame = cap.read()
    if not ret:
        break
    img = cv2.flip(frame, 1)
    h, w = img.shape[:2]
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    res = pose.process(rgb)

    if res.pose_landmarks:
        feats = extract_features(res.pose_landmarks.landmark, w, h)
        interpreter.set_tensor(input_details[0]['index'], feats)
        interpreter.invoke()
        pred = interpreter.get_tensor(output_details[0]['index'])[0]
        label = CLASS_NAMES[np.argmax(pred)]
        confidence = np.max(pred)
        cv2.putText(img, f"{label} ({confidence:.2f})", (10, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 1, (0,255,0), 2)
    else:
        cv2.putText(img, "No pose detected", (10, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 1, (0,0,255), 2)

    cv2.imshow("Lean Direction Detection", img)
    if cv2.waitKey(1) & 0xFF == ord('q'):
        break

cap.release()
cv2.destroyAllWindows()
