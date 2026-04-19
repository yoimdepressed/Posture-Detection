import pandas as pd
import numpy as np
import tensorflow as tf
from sklearn.model_selection import train_test_split
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import Dense, Dropout
from tensorflow.keras.callbacks import EarlyStopping

# ===== CONFIGURATION =====
DATA_FILE = "lean_dataset.csv"
MODEL_NAME = "lean_direction_model.tflite"

# ===== LOAD DATA =====
data = pd.read_csv(DATA_FILE)

# Make sure 'label' exists
if "label" not in data.columns:
    raise ValueError("❌ CSV file must include a 'label' column (0:left, 1:right, 2:others).")

X = data.drop("label", axis=1).values
y = data["label"].values

# ===== SPLIT =====
X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.2, random_state=42, stratify=y
)

# ===== MODEL =====
model = Sequential([
    Dense(64, activation="relu", input_shape=(X.shape[1],)),
    Dropout(0.3),
    Dense(32, activation="relu"),
    Dense(3, activation="softmax")  # 3 classes
])

model.compile(
    optimizer="adam",
    loss="sparse_categorical_crossentropy",
    metrics=["accuracy"]
)

# ===== TRAIN =====
early_stop = EarlyStopping(monitor="val_loss", patience=5, restore_best_weights=True)
history = model.fit(
    X_train, y_train,
    epochs=50,
    batch_size=32,
    validation_data=(X_test, y_test),
    callbacks=[early_stop],
    verbose=1
)

# ===== EVALUATE =====
loss, acc = model.evaluate(X_test, y_test)
print(f"✅ Test Accuracy: {acc:.2f}")

# ===== CONVERT TO TFLITE =====
converter = tf.lite.TFLiteConverter.from_keras_model(model)
tflite_model = converter.convert()

with open(MODEL_NAME, "wb") as f:
    f.write(tflite_model)

print(f"✅ Model saved as {MODEL_NAME}")
