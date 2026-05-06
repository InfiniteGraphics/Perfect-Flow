package com.perfectframe.shader;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.pipeline.RenderTarget;

public interface ShaderPipelineAdapter {
    String id();

    boolean isAvailable(Minecraft minecraft);

    default RenderTarget colorTarget(Minecraft minecraft) {
        return minecraft.getMainRenderTarget();
    }

    default boolean supportsDepthCapture(Minecraft minecraft) {
        return false;
    }

    default String unavailableDepthReason() {
        return "This shader pipeline does not expose a depth target yet.";
    }
}
