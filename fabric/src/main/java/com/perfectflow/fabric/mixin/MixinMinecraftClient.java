package com.perfectflow.fabric.mixin;

import com.perfectflow.capture.CaptureClientHooks;
import com.perfectflow.fabric.PerfectFlowFabric;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MixinMinecraftClient {
    @Inject(method = "tick", at = @At("TAIL"))
    private void perfectflow$afterClientTick(CallbackInfo info) {
        if (PerfectFlowFabric.toggleRecording() != null) {
            while (PerfectFlowFabric.toggleRecording().wasPressed()) {
                CaptureClientHooks.requestToggle();
            }
        }
        CaptureClientHooks.afterClientTick();
    }
}
