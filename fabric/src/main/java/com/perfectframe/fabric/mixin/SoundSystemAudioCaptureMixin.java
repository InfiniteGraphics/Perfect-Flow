package com.perfectframe.fabric.mixin;

import com.perfectframe.platform.Services;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.sound.SoundCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundSystem.class)
public abstract class SoundSystemAudioCaptureMixin {
    @Inject(method = "play", at = @At("RETURN"))
    private void perfectflow$recordPlay(SoundInstance instance, CallbackInfo ci) {
        if (((SoundSystemAccessor) (Object) this).perfectflow$isPlaying(instance)) {
            Services.PLATFORM.clientAccess().gameAudioCapture().onSoundPlayed(this, instance);
        }
    }

    @Inject(method = "stop(Lnet/minecraft/client/sound/SoundInstance;)V", at = @At("TAIL"))
    private void perfectflow$recordStop(SoundInstance instance, CallbackInfo ci) {
        Services.PLATFORM.clientAccess().gameAudioCapture().onSoundStopped(instance);
    }

    @Inject(method = "updateSoundVolume", at = @At("TAIL"))
    private void perfectflow$recordCategoryVolume(SoundCategory category, float volume, CallbackInfo ci) {
        Services.PLATFORM.clientAccess().gameAudioCapture().onCategoryVolumeUpdated(this, category, volume);
    }

    @Inject(method = "pauseAll", at = @At("TAIL"))
    private void perfectflow$recordPauseAll(CallbackInfo ci) {
        Services.PLATFORM.clientAccess().gameAudioCapture().onPauseAll();
    }

    @Inject(method = "resumeAll", at = @At("TAIL"))
    private void perfectflow$recordResumeAll(CallbackInfo ci) {
        Services.PLATFORM.clientAccess().gameAudioCapture().onResume();
    }

    @Inject(method = "stopAll", at = @At("TAIL"))
    private void perfectflow$recordStopAll(CallbackInfo ci) {
        Services.PLATFORM.clientAccess().gameAudioCapture().onSoundEngineDestroyed();
    }
}
