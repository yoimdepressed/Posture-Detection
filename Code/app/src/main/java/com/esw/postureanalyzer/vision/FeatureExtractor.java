package com.esw.postureanalyzer.vision;

import android.util.Log;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.List;

public class FeatureExtractor {
    private static final String TAG = "FeatureExtractor";

    private static float[] toPixel(NormalizedLandmark lm, int width, int height) {
        if (lm == null) return new float[]{0f, 0f};
        return new float[]{lm.x() * width, lm.y() * height};
    }

    private static float angle3Pts(float[] a, float[] b, float[] c) {
        // Vector BA = A - B
        float[] ba = {a[0] - b[0], a[1] - b[1]};
        
        // Vector BC = C - B
        float[] bc = {c[0] - b[0], c[1] - b[1]};
        
        // Dot product
        float dotProduct = ba[0] * bc[0] + ba[1] * bc[1];
        
        // Magnitudes (add small epsilon to prevent division by zero)
        float magBA = (float) Math.sqrt(ba[0] * ba[0] + ba[1] * ba[1]) + 1e-6f;
        float magBC = (float) Math.sqrt(bc[0] * bc[0] + bc[1] * bc[1]) + 1e-6f;
        
        // Cosine of angle (clip to [-1, 1] to handle floating point errors)
        float cosAngle = dotProduct / (magBA * magBC);
        cosAngle = Math.max(-1.0f, Math.min(1.0f, cosAngle));
        
        // Calculate angle in degrees
        float angle = (float) Math.toDegrees(Math.acos(cosAngle));
        
        // Round to 2 decimal places for consistency with training data
        return Math.round(angle * 100.0f) / 100.0f;
    }

    private static float slopeAngle(float[] a, float[] b) {
        return (float) Math.toDegrees(Math.atan2(b[1] - a[1], b[0] - a[0]));
    }

    private static float[] midpoint(float[] p, float[] q) {
        return new float[]{(p[0] + q[0]) / 2.0f, (p[1] + q[1]) / 2.0f};
    }

    // --- SLOUCH MODEL FEATURES ---
    public static float[] getSlouchFeatures(List<NormalizedLandmark> landmarks, int w, int h) {
        float[] leftShoulder = toPixel(landmarks.get(11), w, h);
        float[] rightShoulder = toPixel(landmarks.get(12), w, h);
        float[] leftHip = toPixel(landmarks.get(23), w, h);
        float[] rightHip = toPixel(landmarks.get(24), w, h);

        float torsoTilt = angle3Pts(leftShoulder, leftHip, rightShoulder);
        float leftAngle = angle3Pts(leftShoulder, leftHip, rightHip);
        float rightAngle = angle3Pts(rightShoulder, rightHip, leftHip);

        Log.d(TAG, String.format("Slouch features - torsoTilt: %.2f, leftAngle: %.2f, rightAngle: %.2f",
                torsoTilt, leftAngle, rightAngle));

        return new float[]{torsoTilt, leftAngle, rightAngle};
    }

    // --- CROSS-LEGGED MODEL FEATURES ---
    public static float[] getCrossLeggedFeatures(List<NormalizedLandmark> landmarks, int w, int h) {
        float[] leftHip = toPixel(landmarks.get(23), w, h);
        float[] rightHip = toPixel(landmarks.get(24), w, h);
        float[] leftKnee = toPixel(landmarks.get(25), w, h);
        float[] rightKnee = toPixel(landmarks.get(26), w, h);
        float[] leftAnkle = toPixel(landmarks.get(27), w, h);
        float[] rightAnkle = toPixel(landmarks.get(28), w, h);

        float leftLegAngle = angle3Pts(leftHip, leftKnee, leftAnkle);
        float rightLegAngle = angle3Pts(rightHip, rightKnee, rightAnkle);

        float kneeDist = (float) Math.hypot(leftKnee[0] - rightKnee[0], leftKnee[1] - rightKnee[1]);
        float ankleDist = (float) Math.hypot(leftAnkle[0] - rightAnkle[0], leftAnkle[1] - rightAnkle[1]);
        float hipDist = (float) Math.hypot(leftHip[0] - rightHip[0], leftHip[1] - rightHip[1]) + 1e-6f;

        kneeDist /= hipDist;
        ankleDist /= hipDist;

        float ankleCross = leftAnkle[0] > rightAnkle[0] ? 1.0f : 0.0f;
        float kneeCross = leftKnee[0] > rightKnee[0] ? 1.0f : 0.0f;

        Log.d(TAG, String.format("CrossLegged features - leftLeg: %.2f, rightLeg: %.2f, kneeDist: %.2f, ankleDist: %.2f, ankleCross: %.0f, kneeCross: %.0f",
                leftLegAngle, rightLegAngle, kneeDist, ankleDist, ankleCross, kneeCross));

        return new float[]{leftLegAngle, rightLegAngle, kneeDist, ankleDist, ankleCross, kneeCross};
    }

    // --- LEANING MODEL FEATURES ---
    public static float[] getLeaningFeatures(List<NormalizedLandmark> landmarks, int w, int h) {
        float[] lsh = toPixel(landmarks.get(11), w, h);
        float[] rsh = toPixel(landmarks.get(12), w, h);
        float[] lhip = toPixel(landmarks.get(23), w, h);
        float[] rhip = toPixel(landmarks.get(24), w, h);
        float[] lear = toPixel(landmarks.get(7), w, h);
        float[] rear = toPixel(landmarks.get(8), w, h);

        float lshVis = landmarks.get(11).visibility().orElse(0.0f);
        float rshVis = landmarks.get(12).visibility().orElse(0.0f);
        float lhipVis = landmarks.get(23).visibility().orElse(0.0f);
        float rhipVis = landmarks.get(24).visibility().orElse(0.0f);
        float learVis = landmarks.get(7).visibility().orElse(0.0f);
        float rearVis = landmarks.get(8).visibility().orElse(0.0f);

        float[] midSh = midpoint(lsh, rsh);
        float[] midHip = midpoint(lhip, rhip);

        float[] v = {midSh[0] - midHip[0], midSh[1] - midHip[1]};
        float torsoAngle = (float) Math.toDegrees(Math.atan2(-v[0], -v[1]));
        float shoulderAngle = slopeAngle(lsh, rsh);
        float headTiltAngle = slopeAngle(lear, rear);

        Log.d(TAG, String.format("Lean features - torsoAngle: %.2f, shoulderAngle: %.2f, headTiltAngle: %.2f",
                torsoAngle, shoulderAngle, headTiltAngle));

        return new float[]{torsoAngle, shoulderAngle, headTiltAngle, lshVis, rshVis, lhipVis, rhipVis, learVis, rearVis};
    }
}