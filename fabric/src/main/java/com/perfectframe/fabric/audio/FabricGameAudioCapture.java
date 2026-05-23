package com.perfectframe.fabric.audio;

import com.perfectframe.audio.GameAudioCapture;
import com.perfectframe.capture.CaptureSession;
import com.perfectframe.fabric.mixin.SourceInvoker;
import com.perfectframe.fabric.mixin.SoundSystemAccessor;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.client.sound.StaticSound;
import net.minecraft.client.sound.TickableSoundInstance;
import net.minecraft.client.sound.Source;
import net.minecraft.sound.SoundCategory;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.SOFTLoopback;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class FabricGameAudioCapture implements GameAudioCapture {
    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int BYTES_PER_SAMPLE = CHANNELS * (BITS_PER_SAMPLE / 8);

    private final Queue<Runnable> pendingCommands = new ConcurrentLinkedQueue<>();
    private final Map<SoundInstance, AudioChannelState> activeChannels = new IdentityHashMap<>();
    private final Map<SoundInstance, Integer> delayedReplayFrames = new IdentityHashMap<>();

    private boolean active;
    private long device;
    private long context;
    private Path tempAudioFile;
    private WavWriter wavWriter;
    private double sampleAccumulator;
    private long frameIndex;
    private String supportReason = "";
    private SoundSystemAccessor lastEngineAccessor;

    @Override
    public boolean isSupported() {
        return true;
    }

    @Override
    public String start(CaptureSession session) {
        if (this.active) {
            return null;
        }

        this.tempAudioFile = session.audioTempFile();
        try {
            Files.createDirectories(this.tempAudioFile.getParent());
            this.wavWriter = new WavWriter(this.tempAudioFile, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE);

            this.device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence) null);
            if (this.device == 0L) {
                return "OpenAL Soft loopback is unavailable.";
            }

            ALCCapabilities alcCapabilities = ALC.createCapabilities(this.device);
            if (!alcCapabilities.ALC_SOFT_loopback) {
                return "OpenAL Soft loopback is not supported.";
            }
            if (!SOFTLoopback.alcIsRenderFormatSupportedSOFT(this.device, SAMPLE_RATE, SOFTLoopback.ALC_STEREO_SOFT, SOFTLoopback.ALC_SHORT_SOFT)) {
                return "OpenAL Soft loopback does not support 48 kHz stereo short output.";
            }

            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                IntBuffer attrs = stack.callocInt(7);
                attrs.put(ALC10.ALC_FREQUENCY).put(SAMPLE_RATE);
                attrs.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(SOFTLoopback.ALC_STEREO_SOFT);
                attrs.put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_SHORT_SOFT);
                attrs.put(0);
                attrs.flip();
                this.context = ALC10.alcCreateContext(this.device, attrs);
            }

            if (this.context == 0L) {
                return "Failed to create OpenAL loopback context.";
            }

            ALC10.alcMakeContextCurrent(this.context);
            AL.createCapabilities(alcCapabilities);

            this.active = true;
            this.sampleAccumulator = 0.0D;
            this.frameIndex = 0L;
            this.pendingCommands.clear();
            this.activeChannels.clear();
            this.delayedReplayFrames.clear();
            this.lastEngineAccessor = null;
            this.supportReason = "";
            return null;
        } catch (Exception exception) {
            this.supportReason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            Path failedAudioFile = this.tempAudioFile;
            this.cleanup();
            if (failedAudioFile != null) {
                try {
                    Files.deleteIfExists(failedAudioFile);
                } catch (Exception ignored) {
                }
            }
            return this.supportReason;
        }
    }

    @Override
    public void stop() {
        if (!this.active && this.device == 0L && this.context == 0L) {
            return;
        }

        this.active = false;
        this.pendingCommands.clear();
        this.delayedReplayFrames.clear();
        for (AudioChannelState state : this.activeChannels.values()) {
            state.channel.close();
        }
        this.activeChannels.clear();
        this.flushAndClose();
        this.cleanup();
    }

    @Override
    public void advanceFrame(CaptureSession session) {
        if (!this.active) {
            return;
        }

        this.drainCommands();
        this.updateActiveChannels();
        this.scheduleDelayedReplays();
        this.renderAudioFrame(session);
        this.frameIndex++;
    }

    @Override
    public void onSoundPlayed(Object engine, Object instance) {
        if (!this.active || !(engine instanceof SoundSystemAccessor accessor) || !(instance instanceof SoundInstance soundInstance)) {
            return;
        }
        this.pendingCommands.add(() -> this.playInternal(accessor, soundInstance));
    }

    @Override
    public void onSoundStopped(Object instance) {
        if (!this.active || !(instance instanceof SoundInstance soundInstance)) {
            return;
        }
        this.pendingCommands.add(() -> this.stopInternal(soundInstance));
    }

    @Override
    public void onListenerUpdated(Object transform) {
    }

    @Override
    public void onCategoryVolumeUpdated(Object engine, Object source, float gain) {
        if (!this.active || !(engine instanceof SoundSystemAccessor accessor) || !(source instanceof SoundCategory category)) {
            return;
        }
        this.pendingCommands.add(() -> this.updateCategoryVolumeInternal(accessor, category, gain));
    }

    @Override
    public void onPauseAll() {
        if (!this.active) {
            return;
        }
        this.pendingCommands.add(this::pauseAllInternal);
    }

    @Override
    public void onResume() {
        if (!this.active) {
            return;
        }
        this.pendingCommands.add(this::resumeInternal);
    }

    @Override
    public void onSoundEngineDestroyed() {
        if (!this.active) {
            return;
        }
        this.pendingCommands.add(this::clearActiveChannelsInternal);
    }

    private void drainCommands() {
        Runnable command;
        while ((command = this.pendingCommands.poll()) != null) {
            try {
                command.run();
            } catch (Exception exception) {
                this.supportReason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            }
        }
    }

    private void updateActiveChannels() {
        List<SoundInstance> stoppedInstances = new ArrayList<>();
        for (AudioChannelState state : this.activeChannels.values()) {
            SoundInstance instance = state.instance;
            if (instance instanceof TickableSoundInstance tickable) {
                if (!tickable.canPlay()) {
                    stoppedInstances.add(instance);
                    continue;
                }
                tickable.tick();
                if (tickable.isDone()) {
                    stoppedInstances.add(instance);
                    continue;
                }
            }

            float volume = this.calculateVolume(state.engineAccessor, instance);
            float pitch = this.calculatePitch(state.engineAccessor, instance);
            state.channel.setVolume(volume);
            state.channel.setPitch(pitch);
            state.channel.setPosition(new net.minecraft.util.math.Vec3d(instance.getX(), instance.getY(), instance.getZ()));
            if (state.manualLooping) {
                state.channel.setLooping(false);
            } else {
                state.channel.setLooping(instance.isRepeatable());
            }
            if (instance.getAttenuationType() == SoundInstance.AttenuationType.NONE || instance.isRelative()) {
                state.channel.disableAttenuation();
                state.channel.setRelative(true);
            } else {
                float attenuationDistance = Math.max(instance.getVolume(), 1.0F) * state.sound.getAttenuation();
                state.channel.setAttenuation(attenuationDistance);
                state.channel.setRelative(false);
            }
            state.channel.tick();
            if (!state.channel.isPlaying() && !state.channel.isStopped()) {
                state.channel.play();
            }
            if (state.channel.isStopped()) {
                stoppedInstances.add(instance);
            }
        }

        for (SoundInstance instance : stoppedInstances) {
            this.handleStoppedInstance(instance);
        }
    }

    private void handleStoppedInstance(SoundInstance instance) {
        AudioChannelState state = this.activeChannels.remove(instance);
        if (state == null) {
            return;
        }
        if (state.manualLooping) {
            this.delayedReplayFrames.put(instance, (int) this.frameIndex + Math.max(0, instance.getRepeatDelay()));
        }
        state.channel.close();
    }

    private void scheduleDelayedReplays() {
        if (this.delayedReplayFrames.isEmpty()) {
            return;
        }

        List<SoundInstance> ready = new ArrayList<>();
        for (Map.Entry<SoundInstance, Integer> entry : this.delayedReplayFrames.entrySet()) {
            if (this.frameIndex >= entry.getValue()) {
                ready.add(entry.getKey());
            }
        }

        for (SoundInstance instance : ready) {
            this.delayedReplayFrames.remove(instance);
            if (this.lastEngineAccessor != null) {
                this.playInternal(this.lastEngineAccessor, instance);
            }
        }
    }

    private void renderAudioFrame(CaptureSession session) {
        if (this.wavWriter == null) {
            return;
        }

        this.sampleAccumulator += SAMPLE_RATE / (double) Math.max(1, session.scheduler().targetFps());
        int samples = (int) Math.floor(this.sampleAccumulator);
        if (samples <= 0) {
            return;
        }
        this.sampleAccumulator -= samples;

        int bytes = samples * BYTES_PER_SAMPLE;
        ByteBuffer buffer = BufferUtils.createByteBuffer(bytes);
        SOFTLoopback.alcRenderSamplesSOFT(this.device, buffer, samples);
        buffer.position(0);
        buffer.limit(bytes);
        try {
            this.wavWriter.write(buffer);
        } catch (IOException exception) {
            this.supportReason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
    }

    private void playInternal(SoundSystemAccessor accessor, SoundInstance instance) {
        if (accessor == null || instance == null) {
            return;
        }
        Sound sound = instance.getSound();
        if (sound == null) {
            return;
        }

        this.lastEngineAccessor = accessor;
        float volume = this.calculateVolume(accessor, instance);
        boolean startedSilently = volume == 0.0F;
        if (startedSilently && !instance.canPlay()) {
            return;
        }

        try {
            SoundLoader soundLoader = accessor.perfectflow$getSoundLoader();
            Source channel = SourceInvoker.perfectflow$createSource();
            if (channel == null) {
                return;
            }

            boolean manualLooping = instance.isRepeatable() && instance.getRepeatDelay() > 0;
            float pitch = this.calculatePitch(accessor, instance);
            channel.setPitch(pitch);
            channel.setVolume(volume);
            channel.setPosition(new net.minecraft.util.math.Vec3d(instance.getX(), instance.getY(), instance.getZ()));
            channel.setRelative(instance.isRelative());
            if (instance.getAttenuationType() == SoundInstance.AttenuationType.NONE || instance.isRelative()) {
                channel.disableAttenuation();
            } else {
                float attenuationDistance = Math.max(instance.getVolume(), 1.0F) * Math.max(sound.getAttenuation(), 1);
                channel.setAttenuation(attenuationDistance);
            }

            if (sound.isStreamed()) {
                AudioStream stream = soundLoader.loadStreamed(sound.getLocation(), !manualLooping && instance.isRepeatable()).join();
                channel.setStream(stream);
            } else {
                StaticSound buffer = soundLoader.loadStatic(sound.getLocation()).join();
                channel.setBuffer(buffer);
            }

            if (!manualLooping) {
                channel.setLooping(instance.isRepeatable());
            }
            channel.play();
            this.activeChannels.put(instance, new AudioChannelState(accessor, instance, sound, channel, manualLooping));
        } catch (Exception exception) {
            this.supportReason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
    }

    private void stopInternal(SoundInstance instance) {
        AudioChannelState state = this.activeChannels.remove(instance);
        if (state != null) {
            state.channel.close();
        }
        this.delayedReplayFrames.remove(instance);
    }

    private void updateCategoryVolumeInternal(SoundSystemAccessor accessor, SoundCategory source, float gain) {
        this.lastEngineAccessor = accessor;
        for (AudioChannelState state : this.activeChannels.values()) {
            if (source == null || state.instance.getCategory() == source) {
            state.channel.setVolume(this.calculateVolume(accessor, state.instance));
        }
    }
    }

    private void pauseAllInternal() {
        for (AudioChannelState state : this.activeChannels.values()) {
            state.channel.pause();
        }
    }

    private void resumeInternal() {
        for (AudioChannelState state : this.activeChannels.values()) {
            state.channel.resume();
        }
    }

    private void clearActiveChannelsInternal() {
        for (AudioChannelState state : this.activeChannels.values()) {
            state.channel.close();
        }
        this.activeChannels.clear();
        this.delayedReplayFrames.clear();
    }

    private float calculatePitch(SoundSystemAccessor accessor, SoundInstance instance) {
        return clamp(accessor.perfectflow$getAdjustedPitch(instance), 0.5F, 2.0F);
    }

    private float calculateVolume(SoundSystemAccessor accessor, SoundInstance instance) {
        return clamp(accessor.perfectflow$getAdjustedVolume(instance), 0.0F, 1.0F);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void flushAndClose() {
        if (this.wavWriter != null) {
            try {
                this.wavWriter.close();
            } catch (IOException exception) {
                this.supportReason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            }
            this.wavWriter = null;
        }
    }

    private void cleanup() {
        this.flushAndClose();
        try {
            ALC10.alcMakeContextCurrent(0L);
        } catch (Exception ignored) {
        }
        if (this.context != 0L) {
            ALC10.alcDestroyContext(this.context);
            this.context = 0L;
        }
        if (this.device != 0L) {
            ALC10.alcCloseDevice(this.device);
            this.device = 0L;
        }
        this.sampleAccumulator = 0.0D;
        this.frameIndex = 0L;
        this.tempAudioFile = null;
    }

    private static final class AudioChannelState {
        private final SoundSystemAccessor engineAccessor;
        private final SoundInstance instance;
        private final Sound sound;
        private final Source channel;
        private final boolean manualLooping;

        private AudioChannelState(SoundSystemAccessor engineAccessor, SoundInstance instance, Sound sound, Source channel, boolean manualLooping) {
            this.engineAccessor = engineAccessor;
            this.instance = instance;
            this.sound = sound;
            this.channel = channel;
            this.manualLooping = manualLooping;
        }
    }

    private static final class WavWriter implements AutoCloseable {
        private final RandomAccessFile file;
        private long dataBytes;

        private WavWriter(Path path, int sampleRate, int channels, int bitsPerSample) throws IOException {
            this.file = new RandomAccessFile(path.toFile(), "rw");
            this.file.setLength(0L);
            this.writeHeader(sampleRate, channels, bitsPerSample, 0L);
        }

        private void writeHeader(int sampleRate, int channels, int bitsPerSample, long dataBytes) throws IOException {
            this.file.seek(0L);
            byte[] header = new byte[44];
            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            buffer.putInt((int) (36L + dataBytes));
            buffer.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            buffer.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            buffer.putInt(16);
            buffer.putShort((short) 1);
            buffer.putShort((short) channels);
            buffer.putInt(sampleRate);
            buffer.putInt(sampleRate * channels * bitsPerSample / 8);
            buffer.putShort((short) (channels * bitsPerSample / 8));
            buffer.putShort((short) bitsPerSample);
            buffer.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            buffer.putInt((int) dataBytes);
            this.file.write(header);
        }

        private void write(ByteBuffer buffer) throws IOException {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            this.file.seek(this.file.length());
            this.file.write(bytes);
            this.dataBytes += bytes.length;
        }

        @Override
        public void close() throws IOException {
            this.writeHeader(SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE, this.dataBytes);
            this.file.close();
        }
    }
}
