package com.perfectframe.fabric.config;

import com.perfectframe.CommonClass;
import com.perfectframe.config.PerfectFrameConfig;
import com.perfectframe.platform.Services;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;

public final class PerfectFrameModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            PerfectFrameConfig config = CommonClass.config();
            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setTitle(Component.literal("Perfect Frame"));
            ConfigEntryBuilder entries = builder.entryBuilder();
            ConfigCategory capture = builder.getOrCreateCategory(Component.literal("Capture"));
            capture.addEntry(entries.startIntField(Component.literal("FPS"), config.capture.fps)
                    .setMin(1)
                    .setMax(240)
                    .setDefaultValue(60)
                    .setSaveConsumer(value -> config.capture.fps = value)
                    .build());
            capture.addEntry(entries.startStrField(Component.literal("Output path"), config.capture.outputPath)
                    .setDefaultValue("perfectframe_captures")
                    .setSaveConsumer(value -> config.capture.outputPath = value)
                    .build());
            capture.addEntry(entries.startEnumSelector(Component.literal("Resolution mode"), PerfectFrameConfig.ResolutionMode.class, config.capture.resolutionMode)
                    .setDefaultValue(PerfectFrameConfig.ResolutionMode.NATIVE)
                    .setSaveConsumer(value -> config.capture.resolutionMode = value)
                    .build());
            capture.addEntry(entries.startDoubleField(Component.literal("Resolution scale"), config.capture.resolutionScale)
                    .setMin(0.1D)
                    .setMax(1.0D)
                    .setDefaultValue(1.0D)
                    .setSaveConsumer(value -> config.capture.resolutionScale = value)
                    .build());
            capture.addEntry(entries.startIntField(Component.literal("Output width"), config.capture.outputWidth)
                    .setMin(0)
                    .setMax(7680)
                    .setDefaultValue(0)
                    .setSaveConsumer(value -> config.capture.outputWidth = value)
                    .build());
            capture.addEntry(entries.startIntField(Component.literal("Output height"), config.capture.outputHeight)
                    .setMin(0)
                    .setMax(4320)
                    .setDefaultValue(0)
                    .setSaveConsumer(value -> config.capture.outputHeight = value)
                    .build());
            capture.addEntry(entries.startBooleanToggle(Component.literal("Record alpha"), config.capture.recordAlpha)
                    .setDefaultValue(false)
                    .setSaveConsumer(value -> config.capture.recordAlpha = value)
                    .build());
            capture.addEntry(entries.startBooleanToggle(Component.literal("Record depth"), config.capture.recordDepth)
                    .setDefaultValue(false)
                    .setSaveConsumer(value -> config.capture.recordDepth = value)
                    .build());
            capture.addEntry(entries.startBooleanToggle(Component.literal("Show recording HUD"), config.capture.showRecordingHud)
                    .setDefaultValue(true)
                    .setSaveConsumer(value -> config.capture.showRecordingHud = value)
                    .build());
            ConfigCategory sync = builder.getOrCreateCategory(Component.literal("Sync"));
            sync.addEntry(entries.startBooleanToggle(Component.literal("Enable sync"), config.sync.enabled)
                    .setDefaultValue(true)
                    .setSaveConsumer(value -> config.sync.enabled = value)
                    .build());
            sync.addEntry(entries.startDoubleField(Component.literal("Engine speed"), config.sync.engineSpeed)
                    .setMin(0.01D)
                    .setMax(1200.0D)
                    .setDefaultValue(1.0D)
                    .setSaveConsumer(value -> config.sync.engineSpeed = value)
                    .build());
            ConfigCategory ffmpeg = builder.getOrCreateCategory(Component.literal("FFmpeg"));
            ffmpeg.addEntry(entries.startStrField(Component.literal("FFmpeg executable path"), config.ffmpeg.customPath)
                    .setDefaultValue("")
                    .setSaveConsumer(value -> config.ffmpeg.customPath = value)
                    .build());
            ffmpeg.addEntry(entries.startEnumSelector(Component.literal("Quality preset"), PerfectFrameConfig.QualityPreset.class, config.ffmpeg.qualityPreset)
                    .setDefaultValue(PerfectFrameConfig.QualityPreset.BALANCED)
                    .setSaveConsumer(value -> config.ffmpeg.qualityPreset = value)
                    .build());
            ffmpeg.addEntry(entries.startIntField(Component.literal("Video bitrate kbps"), config.ffmpeg.videoBitrateKbps)
                    .setMin(250)
                    .setMax(100000)
                    .setDefaultValue(8000)
                    .setSaveConsumer(value -> config.ffmpeg.videoBitrateKbps = value)
                    .build());
            ffmpeg.addEntry(entries.startIntField(Component.literal("Writer queue frames"), config.ffmpeg.writerQueueCapacityFrames)
                    .setMin(1)
                    .setMax(240)
                    .setDefaultValue(12)
                    .setSaveConsumer(value -> config.ffmpeg.writerQueueCapacityFrames = value)
                    .build());
            ffmpeg.addEntry(entries.startIntField(Component.literal("Writer stall timeout ms"), config.ffmpeg.writerStallTimeoutMillis)
                    .setMin(1000)
                    .setMax(120000)
                    .setDefaultValue(30000)
                    .setSaveConsumer(value -> config.ffmpeg.writerStallTimeoutMillis = value)
                    .build());
            ffmpeg.addEntry(entries.startStrField(Component.literal("Advanced video args"), config.ffmpeg.videoArgs)
                    .setDefaultValue("")
                    .setSaveConsumer(value -> config.ffmpeg.videoArgs = value)
                    .build());
            builder.setSavingRunnable(() -> {
                config.normalize();
                config.save(Services.PLATFORM.getConfigDirectory());
                CommonClass.reloadConfig();
            });
            return builder.build();
        };
    }
}
