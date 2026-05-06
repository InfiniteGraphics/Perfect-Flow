package com.perfectframe.shader;

import com.perfectframe.platform.Services;
import net.minecraft.client.Minecraft;

public final class IrisPipelineAdapter implements ShaderPipelineAdapter {
    @Override
    public String id() {
        return "iris";
    }

    @Override
    public boolean isAvailable(Minecraft minecraft) {
        return Services.PLATFORM.isModLoaded("iris");
    }

    @Override
    public boolean supportsDepthCapture(Minecraft minecraft) {
        return false;
    }

    @Override
    public String unavailableDepthReason() {
        return "Iris is detected, but the first implementation only routes color through the vanilla fallback. The Iris depth target bridge still needs a concrete API hook.";
    }
}
