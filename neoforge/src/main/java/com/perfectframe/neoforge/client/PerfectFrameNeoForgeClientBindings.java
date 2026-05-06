package com.perfectframe.neoforge.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.perfectframe.Constants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PerfectFrameNeoForgeClientBindings {
    private static final KeyMapping TOGGLE_RECORDING = new KeyMapping(
            "key.perfectframe.toggle_recording",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U,
            "key.categories.perfectframe"
    );

    private PerfectFrameNeoForgeClientBindings() {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        Constants.LOG.info("{} registering NeoForge key mapping {}", Constants.MOD_NAME, TOGGLE_RECORDING.getName());
        event.register(TOGGLE_RECORDING);
    }

    public static boolean consumeToggleClick() {
        return TOGGLE_RECORDING.consumeClick();
    }
}
