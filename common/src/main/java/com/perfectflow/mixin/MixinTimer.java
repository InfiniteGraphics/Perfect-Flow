package com.perfectflow.mixin;

import com.perfectflow.capture.CaptureController;
import com.perfectflow.platform.Services;
import net.minecraft.client.Timer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Timer.class)
public class MixinTimer {
    @Shadow
    public float partialTick;

    @Shadow
    public float tickDelta;

    @Shadow
    private long lastMs;

    @Final
    @Shadow
    private float msPerTick;

    @Inject(method = "advanceTime", at = @At("HEAD"), cancellable = true)
    private void perfectflow$scaleAdvanceTime(long now, CallbackInfoReturnable<Integer> cir) {
        double scale = CaptureController.INSTANCE.recordingTimeScale(Services.PLATFORM.clientAccess().isSingleplayerWorld());
        if (Math.abs(scale - 1.0D) < 0.000_001D) {
            return;
        }

        long elapsedMs = Math.max(0L, now - lastMs);
        float scaledElapsed = (float) elapsedMs * (float) scale;
        tickDelta = scaledElapsed / msPerTick;
        lastMs = now;
        partialTick += tickDelta;
        int ticks = (int) partialTick;
        partialTick -= ticks;
        cir.setReturnValue(ticks);
    }
}
