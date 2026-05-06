package com.perfectframe.neoforge.platform;

import com.perfectframe.platform.services.ClientAccess;
import com.perfectframe.platform.services.IPlatformHelper;
import com.perfectframe.platform.services.MainTargetAccess;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

import java.nio.file.Path;

public final class NeoForgePlatformHelper implements IPlatformHelper {
    private final ClientAccess clientAccess = new NeoForgeClientAccess();

    @Override
    public String getPlatformName() {
        return "NeoForge";
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
    public MainTargetAccess mainTarget() {
        return new NeoForgeMainTargetAccess(Minecraft.getInstance().getMainRenderTarget());
    }
}
