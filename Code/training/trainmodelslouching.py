import pandas as pd
import tensorflow as tf
from tensorflow.keras import layers, models

# ===== CONFIG =====
CSV_FILE = "pose_dataset.csv"
TFLITE_MODEL_FILE = "posture_model.tflite"

# Load dataset
df = pd.read_csv(CSV_FILE)

X = df[['torso_tilt','left_angle','right_angle']].values.astype('float32')
y = df['label'].values.astype('float32')

# Split data
from sklearn.model_selection import train_test_split
X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

# Build simple MLP
model = models.Sequential([
    layers.Input(shape=(3,)),
    layers.Dense(16, activation='relu'),
    layers.Dense(16, activation='relu'),
    layers.Dense(1, activation='sigmoid')
])

model.compile(optimizer='adam', loss='binary_crossentropy', metrics=['accuracy'])
model.summary()

# Train
model.fit(X_train, y_train, epochs=50, batch_size=8, validation_split=0.1)

# Evaluate
loss, acc = model.evaluate(X_test, y_test)
print(f"Test Accuracy: {acc*100:.2f}%")

# Save TensorFlow model
model.save("posture_model.h5")

# Convert to TFLite
converter = tf.lite.TFLiteConverter.from_keras_model(model)
tflite_model = converter.convert()

with open(TFLITE_MODEL_FILE, 'wb') as f:
    f.write(tflite_model)

print(f"TFLite model saved to {TFLITE_MODEL_FILE}")
