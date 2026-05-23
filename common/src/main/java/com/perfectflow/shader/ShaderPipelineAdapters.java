package com.perfectflow.shader;

import com.perfectflow.config.PerfectFlowConfig;
import com.perfectflow.platform.Services;

import java.util.List;

public final class ShaderPipelineAdapters {
    private static final ShaderPipelineAdapter VANILLA = new VanillaPipelineAdapter();
    private static final ShaderPipelineAdapter IRIS = new IrisPipelineAdapter();
    private static final ShaderPipelineAdapter OCULUS = new OculusPipelineAdapter();

    private ShaderPipelineAdapters() {
    }

    public static ShaderPipelineAdapter select(PerfectFlowConfig config) {
        PerfectFlowConfig.ShaderCaptureMode mode = Services.PLATFORM.normalizeShaderCaptureMode(config.shader.captureMode);
        return switch (mode) {
            case VANILLA -> VANILLA;
            case IRIS -> IRIS;
            case OCULUS -> OCULUS;
            case AUTO -> List.of(IRIS, OCULUS, VANILLA).stream()
                    .filter(adapter -> adapter.isAvailable() && adapter.resolve().hasColor())
                    .findFirst()
                    .orElse(VANILLA);
        };
    }
}
