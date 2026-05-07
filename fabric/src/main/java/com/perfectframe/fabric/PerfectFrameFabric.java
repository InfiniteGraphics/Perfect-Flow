package com.perfectframe.fabric;

import com.perfectframe.CommonClass;
import com.perfectframe.capture.CaptureClientHooks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class PerfectFrameFabric implements ClientModInitializer {
    private static KeyBinding toggleRecording;

    @Override
    public void onInitializeClient() {
        CommonClass.init();
        toggleRecording = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.perfectflow.toggle_recording",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                "key.categories.perfectflow"
        ));
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> CaptureClientHooks.renderHud(graphics));
    }

    public static KeyBinding toggleRecording() {
        return toggleRecording;
    }
}
