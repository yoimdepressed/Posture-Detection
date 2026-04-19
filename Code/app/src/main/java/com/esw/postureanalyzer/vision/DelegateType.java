package com.esw.postureanalyzer.vision;

public enum DelegateType {
    CPU("CPU"),
    GPU("GPU"),
    NNAPI("NPU");

    private final String displayName;

    DelegateType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
