package com.perfectflow.fabric.mixin;

import com.perfectflow.capture.CaptureClientHooks;
import com.perfectflow.platform.Services;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraftClient {
    @Inject(method = "tick", at = @At("TAIL"))
    private void perfectflow$afterClientTick(CallbackInfo info) {
        while (Services.PLATFORM.clientAccess().consumeToggleClick()) {
            CaptureClientHooks.requestToggle();
        }
        CaptureClientHooks.afterClientTick();
    }
}
