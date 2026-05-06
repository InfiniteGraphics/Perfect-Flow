package com.perfectframe.shader;

import com.perfectframe.platform.Services;

public final class VanillaPipelineAdapter implements ShaderPipelineAdapter {
    @Override
    public String id() {
        return "vanilla";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public CaptureSource resolve() {
        RenderTargetCaptureAttachment attachment = new RenderTargetCaptureAttachment(Services.PLATFORM.mainTarget());
        return CaptureSource.available(id(), "vanilla/main-target", attachment, attachment);
    }
}
