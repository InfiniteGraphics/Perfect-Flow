package com.perfectframe.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import com.perfectframe.CommonClass;
import com.perfectframe.capture.CaptureClientHooks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class PerfectFrameFabric implements ClientModInitializer {
    private static KeyMapping toggleRecording;

    @Override
    public void onInitializeClient() {
        CommonClass.init();
        toggleRecording = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.perfectframe.toggle_recording",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                "key.categories.perfectframe"
        ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleRecording.consumeClick()) {
                CaptureClientHooks.requestToggle();
            }
        });
        WorldRenderEvents.END.register(context -> CaptureClientHooks.afterWorldRender());
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> CaptureClientHooks.renderHud(graphics));
    }
}
