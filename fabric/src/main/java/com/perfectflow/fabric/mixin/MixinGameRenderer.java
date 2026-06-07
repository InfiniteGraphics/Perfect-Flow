package com.perfectflow.fabric.mixin;

import com.perfectflow.capture.CaptureClientHooks;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class MixinGameRenderer {
    @Inject(method = "render", at = @At("HEAD"))
    private void perfectflow$captureFinalFrame(CallbackInfo info) {
        CaptureClientHooks.captureFinalFrame();
    }
}
