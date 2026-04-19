import cv2
import mediapipe as mp
import numpy as np
import tensorflow as tf

# ===== CONFIG =====
TFLITE_MODEL_FILE = "posture_model.tflite"

# Load TFLite model
interpreter = tf.lite.Interpreter(model_path=TFLITE_MODEL_FILE)
interpreter.allocate_tensors()

input_details = interpreter.get_input_details()
output_details = interpreter.get_output_details()
print("✅ TFLite model loaded successfully")

# Mediapipe setup
mp_pose = mp.solutions.pose
mp_drawing = mp.solutions.drawing_utils

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

    torso_tilt = angle_3pts(left_shoulder, left_hip, right_shoulder)
    left_angle = angle_3pts(left_shoulder, left_hip, right_hip)
    right_angle = angle_3pts(right_shoulder, right_hip, left_hip)

    return [torso_tilt, left_angle, right_angle]

# Live webcam
cap = cv2.VideoCapture(0)
with mp_pose.Pose(min_detection_confidence=0.5, min_tracking_confidence=0.5) as pose:
    while cap.isOpened():
        ret, frame = cap.read()
        if not ret:
            break

        h, w, _ = frame.shape
        frame = cv2.flip(frame, 1)
        results = pose.process(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))

        if results.pose_landmarks:
            mp_drawing.draw_landmarks(frame, results.pose_landmarks, mp_pose.POSE_CONNECTIONS)
            angles = get_upper_body_angles(results.pose_landmarks.landmark, w, h)

            # Run TFLite inference
            input_data = np.array([angles], dtype=np.float32)
            interpreter.set_tensor(input_details[0]['index'], input_data)
            interpreter.invoke()
            output = interpreter.get_tensor(output_details[0]['index'])[0][0]

            label = "Straight" if output >= 0.5 else "Slouching"
            color = (0,255,0) if output >= 0.5 else (0,0,255)
            cv2.putText(frame, f"Posture: {label}", (20,50), cv2.FONT_HERSHEY_SIMPLEX, 1, color, 2)

        cv2.imshow("Live Posture Detection", frame)
        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

cap.release()
cv2.destroyAllWindows()
