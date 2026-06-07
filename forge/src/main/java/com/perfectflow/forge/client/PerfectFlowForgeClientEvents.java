package com.perfectflow.forge.client;

import com.perfectflow.Constants;
import com.perfectflow.capture.CaptureClientHooks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PerfectFlowForgeClientEvents {
    private PerfectFlowForgeClientEvents() {
    }

    @SubscribeEvent
    public static void afterClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        while (PerfectFlowForgeClientBindings.consumeToggleClick()) {
            CaptureClientHooks.requestToggle();
        }
        CaptureClientHooks.afterClientTick();
    }

    @SubscribeEvent
    public static void afterRenderLevel(RenderLevelStageEvent event) {
        String stageName = String.valueOf(event.getStage());
        if ("after_level".equals(stageName) || "after_weather".equals(stageName)) {
            CaptureClientHooks.captureFinalFrame();
        }
    }
}
