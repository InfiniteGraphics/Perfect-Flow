package com.perfectframe.shader;

import com.perfectframe.platform.Services;

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
        String reason = "Oculus is deferred for a later multi-version Forge integration.";
        return CaptureSource.unavailable(id(), "oculus", reason, reason);
    }
}
