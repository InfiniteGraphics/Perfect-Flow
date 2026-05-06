package com.perfectframe.shader;

import com.perfectframe.platform.Services;

public final class IrisPipelineAdapter implements ShaderPipelineAdapter {
    @Override
    public String id() {
        return "iris";
    }

    @Override
    public boolean isAvailable() {
        return Services.PLATFORM.isModLoaded("iris");
    }

    @Override
    public CaptureSource resolve() {
        return Services.PLATFORM.irisCaptureSource();
    }
}
