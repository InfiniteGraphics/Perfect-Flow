package com.perfectframe.shader;

public interface ShaderPipelineAdapter {
    String id();

    boolean isAvailable();

    CaptureSource resolve();
}
