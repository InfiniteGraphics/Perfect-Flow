package com.perfectframe.shader;

import com.perfectframe.config.PerfectFrameConfig;
import net.minecraft.client.Minecraft;

import java.util.List;

public final class ShaderPipelineAdapters {
    private static final ShaderPipelineAdapter VANILLA = new VanillaPipelineAdapter();
    private static final ShaderPipelineAdapter IRIS = new IrisPipelineAdapter();
    private static final ShaderPipelineAdapter OCULUS = new OculusPipelineAdapter();

    private ShaderPipelineAdapters() {
    }

    public static ShaderPipelineAdapter select(Minecraft minecraft, PerfectFrameConfig.ShaderCaptureMode mode) {
        return switch (mode) {
            case VANILLA -> VANILLA;
            case IRIS -> IRIS.isAvailable(minecraft) ? IRIS : VANILLA;
            case OCULUS -> OCULUS.isAvailable(minecraft) ? OCULUS : VANILLA;
            case AUTO -> List.of(IRIS, OCULUS, VANILLA).stream()
                    .filter(adapter -> adapter.isAvailable(minecraft))
                    .findFirst()
                    .orElse(VANILLA);
        };
    }
}
