package com.perfectflow.forge.platform;

import com.perfectflow.platform.services.ClientAccess;
import com.perfectflow.platform.services.IPlatformHelper;
import com.perfectflow.platform.services.MainTargetAccess;
import com.perfectflow.shader.CaptureSource;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;

import java.nio.file.Path;

public final class ForgePlatformHelper implements IPlatformHelper {
    private final ClientAccess clientAccess = new ForgeClientAccess();

    @Override
    public String getPlatformName() {
        return "Forge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    @Override
    public Path getConfigDirectory() {
        return FMLLoader.getGamePath().resolve("config");
    }

    @Override
    public ClientAccess clientAccess() {
        return clientAccess;
    }

    @Override
    public CaptureSource oculusCaptureSource() {
        return OculusCaptureBridge.resolve(Minecraft.getInstance());
    }

    @Override
    public MainTargetAccess mainTarget() {
        return new ForgeMainTargetAccess(Minecraft.getInstance().getMainRenderTarget());
    }
}
