package com.perfectframe.mixin;

import com.perfectframe.capture.CaptureClientHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Inject(method = "tick", at = @At("TAIL"))
    private void perfectframe$afterClientTick(CallbackInfo info) {
        CaptureClientHooks.afterClientTick();
    }
}
