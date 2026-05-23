package com.perfectflow.platform.services;

import com.perfectflow.config.PerfectFlowConfig;
import com.perfectflow.shader.CaptureSource;

import java.nio.file.Path;

public interface IPlatformHelper {
    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    Path getConfigDirectory();

    ClientAccess clientAccess();

    default PerfectFlowConfig.ShaderCaptureMode normalizeShaderCaptureMode(PerfectFlowConfig.ShaderCaptureMode mode) {
        return mode;
    }

    default CaptureSource irisCaptureSource() {
        return CaptureSource.unavailable("iris", "iris", "Iris is not available on this loader.", "Iris is not available on this loader.");
    }

    default MainTargetAccess mainTarget() {
        throw new UnsupportedOperationException("Main render target is not available on this loader.");
    }

    // Reserved for a future Iris internal final-pass implementation. Current Fabric capture
    // uses a later final-present hook and reads the main framebuffer instead.
    default CaptureSource irisFinalPassCaptureSource() {
        return CaptureSource.unavailable("iris", "iris/final-pass-unavailable", "Iris final pass capture is not implemented on this loader.", "Iris final pass capture is not implemented on this loader.");
    }

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }
}
