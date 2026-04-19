"""
Train a small neural network on crosslegged_sitting_data.csv and export to TFLite.

Usage:
    python train_to_tflite.py

Outputs:
    - model.h5               # trained Keras model
    - crosslegged.tflite     # TFLite converted model
    - scaler.npz             # mean/std for input normalization
"""

import numpy as np
import pandas as pd
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers
import os

CSV = "crosslegged_sitting_data.csv"
MODEL_H5 = "model.h5"
TFLITE_FILE = "crosslegged.tflite"
SCALER_FILE = "scaler.npz"
RANDOM_SEED = 42

# --- Load CSV ---
if not os.path.exists(CSV):
    raise FileNotFoundError(f"{CSV} not found. Run the data collection script first.")

df = pd.read_csv(CSV)
# Expected columns: left_leg_angle,right_leg_angle,knee_dist,ankle_dist,ankle_cross,knee_cross,label
expected_cols = [
    'left_leg_angle', 'right_leg_angle',
    'knee_dist', 'ankle_dist',
    'ankle_cross', 'knee_cross', 'label'
]
if not all(c in df.columns for c in expected_cols):
    raise ValueError(f"CSV must contain columns: {expected_cols}")

# --- Prepare data ---
X = df[['left_leg_angle','right_leg_angle','knee_dist','ankle_dist','ankle_cross','knee_cross']].values.astype(np.float32)
y = df['label'].values.astype(np.float32)

# Simple shuffling + train/val split
np.random.seed(RANDOM_SEED)
perm = np.random.permutation(len(X))
X = X[perm]
y = y[perm]

split = int(0.8 * len(X))
X_train, X_val = X[:split], X[split:]
y_train, y_val = y[:split], y[split:]

# --- Normalize (per-feature mean/std) ---
mean = X_train.mean(axis=0)
std = X_train.std(axis=0) + 1e-8
X_train_norm = (X_train - mean) / std
X_val_norm = (X_val - mean) / std

# Save scaler stats
np.savez(SCALER_FILE, mean=mean, std=std)
print(f"Saved scaler stats to {SCALER_FILE}")

# --- Build model ---
tf.random.set_seed(RANDOM_SEED)
model = keras.Sequential([
    layers.Input(shape=(6,), name="features"),
    layers.Dense(32, activation='relu'),
    layers.Dense(16, activation='relu'),
    layers.Dropout(0.2),
    layers.Dense(1, activation='sigmoid')
])

model.compile(optimizer=keras.optimizers.Adam(learning_rate=1e-3),
              loss='binary_crossentropy',
              metrics=['accuracy'])

model.summary()

# --- Train ---
callbacks = [
    keras.callbacks.EarlyStopping(monitor='val_loss', patience=10, restore_best_weights=True)
]

history = model.fit(
    X_train_norm, y_train,
    validation_data=(X_val_norm, y_val),
    epochs=200,
    batch_size=32,
    callbacks=callbacks,
    verbose=2
)

# --- Evaluate ---
val_loss, val_acc = model.evaluate(X_val_norm, y_val, verbose=0)
print(f"Validation loss: {val_loss:.4f}  acc: {val_acc:.4f}")

# --- Save Keras model ---
model.save(MODEL_H5)
print(f"Saved Keras model to {MODEL_H5}")

# --- Convert to TFLite (float32) ---
converter = tf.lite.TFLiteConverter.from_keras_model(model)
# If you want smaller model, you can enable quantization here (post-training quant).
tflite_model = converter.convert()
with open(TFLITE_FILE, "wb") as f:
    f.write(tflite_model)
print(f"Saved TFLite model to {TFLITE_FILE}")

print("Training & conversion complete.")
