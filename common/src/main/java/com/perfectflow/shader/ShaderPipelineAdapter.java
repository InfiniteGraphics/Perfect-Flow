package com.perfectflow.shader;

public interface ShaderPipelineAdapter {
    String id();

    boolean isAvailable();

    CaptureSource resolve();
}
