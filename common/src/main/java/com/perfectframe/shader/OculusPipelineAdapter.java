package com.perfectframe.shader;

import com.perfectframe.platform.Services;
import net.minecraft.client.Minecraft;

public final class OculusPipelineAdapter implements ShaderPipelineAdapter {
    @Override
    public String id() {
        return "oculus";
    }

    @Override
    public boolean isAvailable(Minecraft minecraft) {
        return Services.PLATFORM.isModLoaded("oculus");
    }

    @Override
    public boolean supportsDepthCapture(Minecraft minecraft) {
        return false;
    }

    @Override
    public String unavailableDepthReason() {
        return "Oculus is detected, but the first implementation only routes color through the vanilla fallback. The Oculus depth target bridge still needs a concrete API hook.";
    }
}
