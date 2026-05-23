package com.perfectflow.fabric.mixin;

import com.perfectflow.capture.CaptureClientHooks;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    // Capture immediately before HUD rendering so Fabric/Iris reads the final presented scene
    // from the main framebuffer instead of an earlier world-render stage.
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;render(Lnet/minecraft/client/gui/DrawContext;F)V"))
    private void perfectflow$captureFinalFrame(float tickDelta, long startTime, boolean tick, CallbackInfo info) {
        CaptureClientHooks.captureFinalFrame();
    }
}
