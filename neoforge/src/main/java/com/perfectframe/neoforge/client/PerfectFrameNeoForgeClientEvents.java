package com.perfectframe.neoforge.client;

import com.perfectframe.Constants;
import com.perfectframe.capture.CaptureClientHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.TickEvent;

@Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PerfectFrameNeoForgeClientEvents {
    private static boolean announcedClientLoad;

    private PerfectFrameNeoForgeClientEvents() {
    }

    @SubscribeEvent
    public static void afterClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (!announcedClientLoad) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal("Perfect Frame NeoForge dev build loaded. Toggle recording with U."), false);
                Constants.LOG.info("{} NeoForge client load announcement sent", Constants.MOD_NAME);
                announcedClientLoad = true;
            }
        }

        while (PerfectFrameNeoForgeClientBindings.consumeToggleClick()) {
            CaptureClientHooks.requestToggle();
        }
    }

    @SubscribeEvent
    public static void afterRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            CaptureClientHooks.captureFinalFrame();
        }
    }

    @SubscribeEvent
    public static void afterRenderGui(RenderGuiEvent.Post event) {
        CaptureClientHooks.renderHud(event.getGuiGraphics());
    }
}
