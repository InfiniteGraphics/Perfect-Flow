package com.perfectframe.fabric.platform;

import com.perfectframe.config.PerfectFrameConfig;
import com.perfectframe.fabric.shader.IrisCaptureBridge;
import com.perfectframe.platform.services.ClientAccess;
import com.perfectframe.platform.services.IPlatformHelper;
import com.perfectframe.platform.services.MainTargetAccess;
import com.perfectframe.shader.CaptureSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;

public final class FabricPlatformHelper implements IPlatformHelper {
    private final ClientAccess clientAccess = new FabricClientAccess();

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public Path getConfigDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public ClientAccess clientAccess() {
        return clientAccess;
    }

    @Override
    public PerfectFrameConfig.ShaderCaptureMode normalizeShaderCaptureMode(PerfectFrameConfig.ShaderCaptureMode mode) {
        return mode == PerfectFrameConfig.ShaderCaptureMode.OCULUS ? PerfectFrameConfig.ShaderCaptureMode.IRIS : mode;
    }

    @Override
    public CaptureSource irisCaptureSource() {
        if (!isModLoaded("iris")) {
            String reason = "Iris mode requires Iris to be installed.";
            return CaptureSource.unavailable("iris", "iris/missing", reason, reason);
        }
        return IrisCaptureBridge.resolve(MinecraftClient.getInstance());
    }

    @Override
    public MainTargetAccess mainTarget() {
        return new FabricMainTargetAccess(MinecraftClient.getInstance().getFramebuffer());
    }
}
