package com.perfectflow.shader;

import com.perfectflow.platform.Services;

public final class OculusPipelineAdapter implements ShaderPipelineAdapter {
    @Override
    public String id() {
        return "oculus";
    }

    @Override
    public boolean isAvailable() {
        return Services.PLATFORM.isModLoaded("oculus");
    }

    @Override
    public CaptureSource resolve() {
        return Services.PLATFORM.oculusCaptureSource();
    }
}
