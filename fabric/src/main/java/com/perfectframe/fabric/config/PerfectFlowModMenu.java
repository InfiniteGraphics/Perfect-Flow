package com.perfectframe.fabric.config;

import com.perfectframe.CommonClass;
import com.perfectframe.config.PerfectFlowConfig;
import com.perfectframe.platform.Services;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.text.Text;

public final class PerfectFlowModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            PerfectFlowConfig config = CommonClass.config();
            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setTitle(Text.literal("PerfectFlow"));
            ConfigEntryBuilder entries = builder.entryBuilder();
            ConfigCategory capture = builder.getOrCreateCategory(Text.literal("Capture"));
            capture.addEntry(entries.startIntField(Text.literal("FPS"), config.capture.fps)
                    .setMin(1)
                    .setMax(240)
                    .setDefaultValue(60)
                    .setSaveConsumer(value -> config.capture.fps = value)
                    .build());
            capture.addEntry(entries.startStrField(Text.literal("Output path"), config.capture.outputPath)
                    .setDefaultValue("perfectflow_captures")
                    .setSaveConsumer(value -> config.capture.outputPath = value)
                    .build());
            capture.addEntry(entries.startEnumSelector(Text.literal("Resolution mode"), PerfectFlowConfig.ResolutionMode.class, config.capture.resolutionMode)
                    .setDefaultValue(PerfectFlowConfig.ResolutionMode.NATIVE)
                    .setSaveConsumer(value -> config.capture.resolutionMode = value)
                    .build());
            capture.addEntry(entries.startDoubleField(Text.literal("Resolution scale"), config.capture.resolutionScale)
                    .setMin(0.1D)
                    .setMax(1.0D)
                    .setDefaultValue(1.0D)
                    .setSaveConsumer(value -> config.capture.resolutionScale = value)
                    .build());
            capture.addEntry(entries.startIntField(Text.literal("Output width"), config.capture.outputWidth)
                    .setMin(0)
                    .setMax(7680)
                    .setDefaultValue(0)
                    .setSaveConsumer(value -> config.capture.outputWidth = value)
                    .build());
            capture.addEntry(entries.startIntField(Text.literal("Output height"), config.capture.outputHeight)
                    .setMin(0)
                    .setMax(4320)
                    .setDefaultValue(0)
                    .setSaveConsumer(value -> config.capture.outputHeight = value)
                    .build());
            capture.addEntry(entries.startBooleanToggle(Text.literal("Record alpha"), config.capture.recordAlpha)
                    .setDefaultValue(false)
                    .setSaveConsumer(value -> config.capture.recordAlpha = value)
                    .build());
            capture.addEntry(entries.startBooleanToggle(Text.literal("Record depth"), config.capture.recordDepth)
                    .setDefaultValue(false)
                    .setSaveConsumer(value -> config.capture.recordDepth = value)
                    .build());
            capture.addEntry(entries.startBooleanToggle(Text.literal("Show recording HUD"), config.capture.showRecordingHud)
                    .setDefaultValue(true)
                    .setSaveConsumer(value -> config.capture.showRecordingHud = value)
                    .build());
            ConfigCategory motionBlur = builder.getOrCreateCategory(Text.literal("Motion Blur"));
            motionBlur.addEntry(entries.startBooleanToggle(Text.literal("Enable motion blur"), config.motionBlur.enabled)
                    .setDefaultValue(false)
                    .setSaveConsumer(value -> config.motionBlur.enabled = value)
                    .build());
            motionBlur.addEntry(entries.startEnumSelector(Text.literal("Mode"), PerfectFlowConfig.MotionBlurMode.class, config.motionBlur.mode)
                    .setDefaultValue(PerfectFlowConfig.MotionBlurMode.FRAME_BLEND)
                    .setSaveConsumer(value -> config.motionBlur.mode = value)
                    .build());
            motionBlur.addEntry(entries.startEnumSelector(Text.literal("Processing path"), FabricMotionBlurPath.class, FabricMotionBlurPath.from(config.motionBlur.path))
                    .setDefaultValue(FabricMotionBlurPath.EXPORTER_THREAD)
                    .setTooltip(Text.literal("FFmpeg filter mode only affects MP4 output. Exporter thread mode keeps the current behavior."))
                    .setSaveConsumer(value -> config.motionBlur.path = value.toConfigPath())
                    .build());
            motionBlur.addEntry(entries.startDoubleField(Text.literal("Shutter fraction"), config.motionBlur.shutterFraction)
                    .setMin(0.0D)
                    .setMax(1.0D)
                    .setDefaultValue(0.5D)
                    .setSaveConsumer(value -> config.motionBlur.shutterFraction = value)
                    .build());
            motionBlur.addEntry(entries.startIntField(Text.literal("Accumulation samples"), config.motionBlur.sampleCount)
                    .setMin(2)
                    .setMax(16)
                    .setDefaultValue(4)
                    .setSaveConsumer(value -> config.motionBlur.sampleCount = value)
                    .build());
            motionBlur.addEntry(entries.startIntField(Text.literal("Blend history frames"), config.motionBlur.blendFrameCount)
                    .setMin(2)
                    .setMax(16)
                    .setDefaultValue(4)
                    .setSaveConsumer(value -> config.motionBlur.blendFrameCount = value)
                    .build());
            ConfigCategory shader = builder.getOrCreateCategory(Text.literal("Shader"));
            shader.addEntry(entries.startEnumSelector(Text.literal("Capture mode"), FabricShaderCaptureMode.class, FabricShaderCaptureMode.from(config.shader.captureMode))
                    .setDefaultValue(FabricShaderCaptureMode.AUTO)
                    .setTooltip(Text.literal("IRIS requires Iris with an active shader pack. OCULUS config values are treated as IRIS on Fabric 1.20.4."))
                    .setSaveConsumer(value -> config.shader.captureMode = value.toConfigMode())
                    .build());
            ConfigCategory sync = builder.getOrCreateCategory(Text.literal("Sync"));
            sync.addEntry(entries.startEnumSelector(Text.literal("Sync mode"), FabricSyncMode.class, FabricSyncMode.from(config.sync.mode))
                    .setDefaultValue(FabricSyncMode.NORMAL)
                    .setTooltip(Text.literal("Normal uses the stronger singleplayer sync path and automatically downgrades to Client Only in multiplayer."))
                    .setSaveConsumer(value -> config.sync.mode = value.toConfigMode())
                    .build());
            sync.addEntry(entries.startDoubleField(Text.literal("Engine speed"), config.sync.engineSpeed)
                    .setMin(0.01D)
                    .setMax(1200.0D)
                    .setDefaultValue(1.0D)
                    .setSaveConsumer(value -> config.sync.engineSpeed = value)
                    .build());
            ConfigCategory ffmpeg = builder.getOrCreateCategory(Text.literal("FFmpeg"));
            ffmpeg.addEntry(entries.startStrField(Text.literal("FFmpeg executable path"), config.ffmpeg.customPath)
                    .setDefaultValue("")
                    .setSaveConsumer(value -> config.ffmpeg.customPath = value)
                    .build());
            ffmpeg.addEntry(entries.startEnumSelector(Text.literal("Quality preset"), PerfectFlowConfig.QualityPreset.class, config.ffmpeg.qualityPreset)
                    .setDefaultValue(PerfectFlowConfig.QualityPreset.BALANCED)
                    .setSaveConsumer(value -> config.ffmpeg.qualityPreset = value)
                    .build());
            ffmpeg.addEntry(entries.startIntField(Text.literal("Video bitrate kbps"), config.ffmpeg.videoBitrateKbps)
                    .setMin(250)
                    .setMax(100000)
                    .setDefaultValue(8000)
                    .setSaveConsumer(value -> config.ffmpeg.videoBitrateKbps = value)
                    .build());
            ffmpeg.addEntry(entries.startIntField(Text.literal("Writer queue frames"), config.ffmpeg.writerQueueCapacityFrames)
                    .setMin(1)
                    .setMax(240)
                    .setDefaultValue(12)
                    .setSaveConsumer(value -> config.ffmpeg.writerQueueCapacityFrames = value)
                    .build());
            ffmpeg.addEntry(entries.startIntField(Text.literal("Writer stall timeout ms"), config.ffmpeg.writerStallTimeoutMillis)
                    .setMin(1000)
                    .setMax(120000)
                    .setDefaultValue(30000)
                    .setSaveConsumer(value -> config.ffmpeg.writerStallTimeoutMillis = value)
                    .build());
            ffmpeg.addEntry(entries.startStrField(Text.literal("Advanced video args"), config.ffmpeg.videoArgs)
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

    private enum FabricShaderCaptureMode {
        AUTO,
        VANILLA,
        IRIS;

        private static FabricShaderCaptureMode from(PerfectFlowConfig.ShaderCaptureMode mode) {
            return switch (mode) {
                case VANILLA -> VANILLA;
                case IRIS, OCULUS -> IRIS;
                case AUTO -> AUTO;
            };
        }

        private PerfectFlowConfig.ShaderCaptureMode toConfigMode() {
            return switch (this) {
                case AUTO -> PerfectFlowConfig.ShaderCaptureMode.AUTO;
                case VANILLA -> PerfectFlowConfig.ShaderCaptureMode.VANILLA;
                case IRIS -> PerfectFlowConfig.ShaderCaptureMode.IRIS;
            };
        }
    }

    private enum FabricMotionBlurPath {
        EXPORTER_THREAD,
        FFMPEG_FILTER;

        private static FabricMotionBlurPath from(PerfectFlowConfig.MotionBlurPath path) {
            return path == PerfectFlowConfig.MotionBlurPath.FFMPEG_FILTER ? FFMPEG_FILTER : EXPORTER_THREAD;
        }

        private PerfectFlowConfig.MotionBlurPath toConfigPath() {
            return this == FFMPEG_FILTER
                    ? PerfectFlowConfig.MotionBlurPath.FFMPEG_FILTER
                    : PerfectFlowConfig.MotionBlurPath.EXPORTER_THREAD;
        }
    }

    private enum FabricSyncMode {
        NORMAL,
        CLIENT_ONLY;

        private static FabricSyncMode from(PerfectFlowConfig.SyncMode mode) {
            return mode == PerfectFlowConfig.SyncMode.CLIENT_ONLY ? CLIENT_ONLY : NORMAL;
        }

        private PerfectFlowConfig.SyncMode toConfigMode() {
            return this == CLIENT_ONLY ? PerfectFlowConfig.SyncMode.CLIENT_ONLY : PerfectFlowConfig.SyncMode.NORMAL;
        }
    }
}
