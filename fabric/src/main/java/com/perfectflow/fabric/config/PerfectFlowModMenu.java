package com.perfectflow.fabric.config;

import com.perfectflow.CommonClass;
import com.perfectflow.config.PerfectFlowConfig;
import com.perfectflow.platform.Services;
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
                    .setTitle(key("title"));
            ConfigEntryBuilder entries = builder.entryBuilder();
            ConfigCategory capture = builder.getOrCreateCategory(key("category.capture"));
            capture.addEntry(entries.startIntField(key("option.capture.fps"), config.capture.fps)
                    .setMin(1)
                    .setMax(240)
                    .setDefaultValue(60)
                    .setSaveConsumer(value -> config.capture.fps = value)
                    .build());
            capture.addEntry(entries.startStrField(key("option.capture.output_path"), config.capture.outputPath)
                    .setDefaultValue("perfectflow_captures")
                    .setSaveConsumer(value -> config.capture.outputPath = value)
                    .build());
            capture.addEntry(entries.startEnumSelector(key("option.capture.resolution_mode"), PerfectFlowConfig.ResolutionMode.class, config.capture.resolutionMode)
                    .setDefaultValue(PerfectFlowConfig.ResolutionMode.NATIVE)
                    .setEnumNameProvider(value -> enumKey("resolution_mode", value.name().toLowerCase()))
                    .setSaveConsumer(value -> config.capture.resolutionMode = value)
                    .build());
            capture.addEntry(entries.startDoubleField(key("option.capture.resolution_scale"), config.capture.resolutionScale)
                    .setMin(0.1D)
                    .setMax(1.0D)
                    .setDefaultValue(1.0D)
                    .setSaveConsumer(value -> config.capture.resolutionScale = value)
                    .build());
            capture.addEntry(entries.startIntField(key("option.capture.output_width"), config.capture.outputWidth)
                    .setMin(0)
                    .setMax(7680)
                    .setDefaultValue(0)
                    .setSaveConsumer(value -> config.capture.outputWidth = value)
                    .build());
            capture.addEntry(entries.startIntField(key("option.capture.output_height"), config.capture.outputHeight)
                    .setMin(0)
                    .setMax(4320)
                    .setDefaultValue(0)
                    .setSaveConsumer(value -> config.capture.outputHeight = value)
                    .build());
            capture.addEntry(entries.startBooleanToggle(key("option.capture.record_alpha"), config.capture.recordAlpha)
                    .setDefaultValue(false)
                    .setSaveConsumer(value -> config.capture.recordAlpha = value)
                    .build());
            capture.addEntry(entries.startBooleanToggle(key("option.capture.record_depth"), config.capture.recordDepth)
                    .setDefaultValue(false)
                    .setSaveConsumer(value -> config.capture.recordDepth = value)
                    .build());
            capture.addEntry(entries.startBooleanToggle(key("option.capture.show_recording_hud"), config.capture.showRecordingHud)
                    .setDefaultValue(true)
                    .setSaveConsumer(value -> config.capture.showRecordingHud = value)
                    .build());
            ConfigCategory audio = builder.getOrCreateCategory(key("category.audio"));
            audio.addEntry(entries.startBooleanToggle(key("option.audio.enable_recording"), config.audio.enabled)
                    .setDefaultValue(false)
                    .setTooltip(key("tooltip.audio.enable_recording"))
                    .setSaveConsumer(value -> config.audio.enabled = value)
                    .build());
            audio.addEntry(entries.startEnumSelector(key("option.audio.source"), FabricAudioMode.class, FabricAudioMode.from(config.audio.mode))
                    .setDefaultValue(FabricAudioMode.PROCESS_OUTPUT)
                    .setEnumNameProvider(value -> enumKey("audio_mode", value.name().toLowerCase()))
                    .setTooltip(key("tooltip.audio.source"))
                    .setSaveConsumer(value -> config.audio.mode = value.toConfigMode())
                    .build());
            ConfigCategory motionBlur = builder.getOrCreateCategory(key("category.motion_blur"));
            motionBlur.addEntry(entries.startBooleanToggle(key("option.motion_blur.enable"), config.motionBlur.enabled)
                    .setDefaultValue(false)
                    .setSaveConsumer(value -> config.motionBlur.enabled = value)
                    .build());
            motionBlur.addEntry(entries.startEnumSelector(key("option.motion_blur.mode"), PerfectFlowConfig.MotionBlurMode.class, config.motionBlur.mode)
                    .setDefaultValue(PerfectFlowConfig.MotionBlurMode.FRAME_BLEND)
                    .setEnumNameProvider(value -> enumKey("motion_blur_mode", value.name().toLowerCase()))
                    .setSaveConsumer(value -> config.motionBlur.mode = value)
                    .build());
            motionBlur.addEntry(entries.startEnumSelector(key("option.motion_blur.processing_path"), FabricMotionBlurPath.class, FabricMotionBlurPath.from(config.motionBlur.path))
                    .setDefaultValue(FabricMotionBlurPath.EXPORTER_THREAD)
                    .setEnumNameProvider(value -> enumKey("motion_blur_path", value.name().toLowerCase()))
                    .setTooltip(key("tooltip.motion_blur.processing_path"))
                    .setSaveConsumer(value -> config.motionBlur.path = value.toConfigPath())
                    .build());
            motionBlur.addEntry(entries.startDoubleField(key("option.motion_blur.shutter_fraction"), config.motionBlur.shutterFraction)
                    .setMin(0.0D)
                    .setMax(1.0D)
                    .setDefaultValue(0.5D)
                    .setSaveConsumer(value -> config.motionBlur.shutterFraction = value)
                    .build());
            motionBlur.addEntry(entries.startIntField(key("option.motion_blur.accumulation_samples"), config.motionBlur.sampleCount)
                    .setMin(2)
                    .setMax(16)
                    .setDefaultValue(4)
                    .setSaveConsumer(value -> config.motionBlur.sampleCount = value)
                    .build());
            motionBlur.addEntry(entries.startIntField(key("option.motion_blur.blend_history_frames"), config.motionBlur.blendFrameCount)
                    .setMin(2)
                    .setMax(16)
                    .setDefaultValue(4)
                    .setSaveConsumer(value -> config.motionBlur.blendFrameCount = value)
                    .build());
            ConfigCategory shader = builder.getOrCreateCategory(key("category.shader"));
            shader.addEntry(entries.startEnumSelector(key("option.shader.capture_mode"), FabricShaderCaptureMode.class, FabricShaderCaptureMode.from(config.shader.captureMode))
                    .setDefaultValue(FabricShaderCaptureMode.AUTO)
                    .setEnumNameProvider(value -> enumKey("shader_capture_mode", value.name().toLowerCase()))
                    .setTooltip(key("tooltip.shader.capture_mode"))
                    .setSaveConsumer(value -> config.shader.captureMode = value.toConfigMode())
                    .build());
            ConfigCategory sync = builder.getOrCreateCategory(key("category.sync"));
            sync.addEntry(entries.startEnumSelector(key("option.sync.mode"), FabricSyncMode.class, FabricSyncMode.from(config.sync.mode))
                    .setDefaultValue(FabricSyncMode.NORMAL)
                    .setEnumNameProvider(value -> enumKey("sync_mode", value.name().toLowerCase()))
                    .setTooltip(key("tooltip.sync.mode"))
                    .setSaveConsumer(value -> config.sync.mode = value.toConfigMode())
                    .build());
            sync.addEntry(entries.startDoubleField(key("option.sync.engine_speed"), config.sync.engineSpeed)
                    .setMin(0.01D)
                    .setMax(1200.0D)
                    .setDefaultValue(1.0D)
                    .setSaveConsumer(value -> config.sync.engineSpeed = value)
                    .build());
            ConfigCategory ffmpeg = builder.getOrCreateCategory(key("category.ffmpeg"));
            ffmpeg.addEntry(entries.startStrField(key("option.ffmpeg.executable_path"), config.ffmpeg.customPath)
                    .setDefaultValue("")
                    .setSaveConsumer(value -> config.ffmpeg.customPath = value)
                    .build());
            ffmpeg.addEntry(entries.startEnumSelector(key("option.ffmpeg.quality_preset"), PerfectFlowConfig.QualityPreset.class, config.ffmpeg.qualityPreset)
                    .setDefaultValue(PerfectFlowConfig.QualityPreset.BALANCED)
                    .setEnumNameProvider(value -> enumKey("quality_preset", value.name().toLowerCase()))
                    .setSaveConsumer(value -> config.ffmpeg.qualityPreset = value)
                    .build());
            ffmpeg.addEntry(entries.startIntField(key("option.ffmpeg.video_bitrate_kbps"), config.ffmpeg.videoBitrateKbps)
                    .setMin(250)
                    .setMax(100000)
                    .setDefaultValue(8000)
                    .setSaveConsumer(value -> config.ffmpeg.videoBitrateKbps = value)
                    .build());
            ffmpeg.addEntry(entries.startIntField(key("option.ffmpeg.writer_queue_frames"), config.ffmpeg.writerQueueCapacityFrames)
                    .setMin(1)
                    .setMax(240)
                    .setDefaultValue(12)
                    .setSaveConsumer(value -> config.ffmpeg.writerQueueCapacityFrames = value)
                    .build());
            ffmpeg.addEntry(entries.startIntField(key("option.ffmpeg.writer_stall_timeout_ms"), config.ffmpeg.writerStallTimeoutMillis)
                    .setMin(1000)
                    .setMax(120000)
                    .setDefaultValue(30000)
                    .setSaveConsumer(value -> config.ffmpeg.writerStallTimeoutMillis = value)
                    .build());
            ffmpeg.addEntry(entries.startStrField(key("option.ffmpeg.advanced_video_args"), config.ffmpeg.videoArgs)
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

    private static Text key(String path) {
        return Text.translatable("perfectflow.config." + path);
    }

    private static Text enumKey(String group, String value) {
        return Text.translatable("perfectflow.config.enum." + group + "." + value);
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

    private enum FabricAudioMode {
        PROCESS_OUTPUT;

        private static FabricAudioMode from(PerfectFlowConfig.AudioMode mode) {
            return PROCESS_OUTPUT;
        }

        private PerfectFlowConfig.AudioMode toConfigMode() {
            return PerfectFlowConfig.AudioMode.PROCESS_OUTPUT;
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
