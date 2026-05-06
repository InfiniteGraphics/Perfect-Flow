package com.perfectframe.shader;

import net.minecraft.client.Minecraft;

public final class VanillaPipelineAdapter implements ShaderPipelineAdapter {
    @Override
    public String id() {
        return "vanilla";
    }

    @Override
    public boolean isAvailable(Minecraft minecraft) {
        return true;
    }

    @Override
    public boolean supportsDepthCapture(Minecraft minecraft) {
        return true;
    }
}
