package com.perfectframe.fabric.mixin;

import net.minecraft.client.sound.SoundLoader;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundEngine;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SoundSystem.class)
public interface SoundSystemAccessor {
    @Accessor("soundLoader")
    SoundLoader perfectflow$getSoundLoader();

    @Accessor("soundEngine")
    SoundEngine perfectflow$getSoundEngine();

    @Invoker("getAdjustedVolume")
    float perfectflow$getAdjustedVolume(SoundInstance sound);

    @Invoker("getAdjustedPitch")
    float perfectflow$getAdjustedPitch(SoundInstance sound);

    @Invoker("isPlaying")
    boolean perfectflow$isPlaying(SoundInstance sound);
}
