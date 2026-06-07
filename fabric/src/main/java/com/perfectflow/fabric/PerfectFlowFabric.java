package com.perfectflow.fabric;

import com.perfectflow.CommonClass;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class PerfectFlowFabric implements ClientModInitializer {
    private static KeyMapping toggleRecording;

    @Override
    public void onInitializeClient() {
        CommonClass.init();
        toggleRecording = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.perfectflow.toggle_recording",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                "key.categories.perfectflow"
        ));
    }

    public static KeyMapping toggleRecording() {
        return toggleRecording;
    }
}
