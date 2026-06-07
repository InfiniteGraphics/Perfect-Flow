package com.perfectflow.forge.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

public final class PerfectFlowForgeClientBindings {
    private static final KeyMapping TOGGLE_RECORDING = new KeyMapping(
            "key.perfectflow.toggle_recording",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U,
            "key.categories.perfectflow"
    );

    private PerfectFlowForgeClientBindings() {
    }

    public static void register(IEventBus modBus) {
        try {
            Class<?> eventClass = Class.forName("net.minecraftforge.client.event.RegisterKeyMappingsEvent");
            registerModernKeyMappingListener(modBus, eventClass);
            return;
        } catch (ClassNotFoundException ignored) {
        }

        modBus.addListener(PerfectFlowForgeClientBindings::onClientSetup);
    }

    public static boolean consumeToggleClick() {
        return TOGGLE_RECORDING.consumeClick();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerModernKeyMappingListener(IEventBus modBus, Class<?> eventClass) {
        modBus.addListener(EventPriority.NORMAL, false, (Class) eventClass, event -> {
            try {
                eventClass.getMethod("register", KeyMapping.class).invoke(event, TOGGLE_RECORDING);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to register Forge key mapping.", exception);
            }
        });
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                Class<?> clientRegistryClass = Class.forName("net.minecraftforge.client.ClientRegistry");
                clientRegistryClass.getMethod("registerKeyBinding", KeyMapping.class).invoke(null, TOGGLE_RECORDING);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to register Forge key mapping through ClientRegistry.", exception);
            }
        });
    }
}
