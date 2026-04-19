package com.esw.postureanalyzer.vision;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.List;

/**
 * Evaluation metrics for pose estimation quality.
 * Based on: https://stasiuk.medium.com/pose-estimation-metrics-844c07ba0a78
 *
 * Note: Since we don't have ground truth in live inference, these provide
 * confidence-based quality estimates rather than true accuracy metrics.
 */
public class EvaluationMetrics {

    /**
     * PDJ (Percentage of Detected Joints) - adapted for live inference.
     * Measures what percentage of keypoints are detected with high confidence.
     *
     * @param landmarks List of detected pose landmarks
     * @param visibilityThreshold Minimum visibility score (0.0-1.0)
     * @return Percentage of confident detections (0-100)
     */
    public static double calculatePDJ(List<NormalizedLandmark> landmarks, double visibilityThreshold) {
        if (landmarks == null || landmarks.isEmpty()) {
            return 0.0;
        }

        int confidentKeypoints = 0;
        int totalKeypoints = 0;

        // Check key body points (excluding face landmarks for simplicity)
        int[] keyJoints = {11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28}; // shoulders, elbows, wrists, hips, knees, ankles

        for (int idx : keyJoints) {
            if (idx < landmarks.size()) {
                totalKeypoints++;
                float visibility = landmarks.get(idx).visibility().orElse(0.0f);
                if (visibility >= visibilityThreshold) {
                    confidentKeypoints++;
                }
            }
        }

        return totalKeypoints > 0 ? (confidentKeypoints * 100.0 / totalKeypoints) : 0.0;
    }

    /**
     * OKS (Object Keypoint Similarity) - adapted for live inference.
     * Estimates pose quality based on visibility and spatial consistency.
     *
     * Original OKS formula requires ground truth. This adaptation provides
     * a quality score based on:
     * 1. Visibility scores
     * 2. Spatial consistency between connected joints
     *
     * @param landmarks List of detected pose landmarks
     * @return Quality score (0-100), higher is better
     */
    public static double calculateOKS(List<NormalizedLandmark> landmarks) {
        if (landmarks == null || landmarks.isEmpty()) {
            return 0.0;
        }

        double totalScore = 0.0;
        int numEvaluated = 0;

        // Define joint pairs for consistency check
        int[][] jointPairs = {
                {11, 13}, {13, 15}, // left arm
                {12, 14}, {14, 16}, // right arm
                {11, 23}, {12, 24}, // torso
                {23, 25}, {25, 27}, // left leg
                {24, 26}, {26, 28}  // right leg
        };

        for (int[] pair : jointPairs) {
            if (pair[0] < landmarks.size() && pair[1] < landmarks.size()) {
                NormalizedLandmark lm1 = landmarks.get(pair[0]);
                NormalizedLandmark lm2 = landmarks.get(pair[1]);

                float vis1 = lm1.visibility().orElse(0.0f);
                float vis2 = lm2.visibility().orElse(0.0f);

                // Average visibility as confidence
                double avgVisibility = (vis1 + vis2) / 2.0;

                // Check if distance is reasonable (not too far, not too close)
                double dx = lm1.x() - lm2.x();
                double dy = lm1.y() - lm2.y();
                double distance = Math.sqrt(dx * dx + dy * dy);

                // Penalize if distance is unrealistic (>0.6 normalized units or <0.02)
                double distanceScore = 1.0;
                if (distance > 0.6 || distance < 0.02) {
                    distanceScore = 0.5;
                }

                totalScore += avgVisibility * distanceScore;
                numEvaluated++;
            }
        }

        return numEvaluated > 0 ? (totalScore * 100.0 / numEvaluated) : 0.0;
    }

    /**
     * Combined quality metric for display.
     *
     * @param landmarks List of detected pose landmarks
     * @return Formatted string with both metrics
     */
    public static String getQualityMetrics(List<NormalizedLandmark> landmarks) {
        if (landmarks == null || landmarks.isEmpty()) {
            return "PDJ: 0% / OKS: 0%";
        }

        double pdj = calculatePDJ(landmarks, 0.5); // 50% visibility threshold
        double oks = calculateOKS(landmarks);

        return String.format("PDJ: %.0f%% / OKS: %.0f%%", pdj, oks);
    }
}