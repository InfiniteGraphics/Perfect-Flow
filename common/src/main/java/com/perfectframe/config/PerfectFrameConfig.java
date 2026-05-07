package com.perfectframe.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.perfectframe.Constants;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Type;

public class PerfectFrameConfig {
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(FfmpegMode.class, new FfmpegModeAdapter())
            .setPrettyPrinting()
            .create();
    private static final String FILE_NAME = Constants.MOD_ID + ".json";
    private static final String LEGACY_DEFAULT_VIDEO_ARGS = "-y -f rawvideo -pix_fmt %PIX_FMT% -s %WIDTH%x%HEIGHT% -r %FPS% -i - -vf vflip -c:v libx264 -preset ultrafast -tune zerolatency -qp 18 -pix_fmt yuv420p %NAME%_%STREAM%.mp4";
    private static final String PREVIOUS_DEFAULT_VIDEO_ARGS = "-y -f rawvideo -pix_fmt %PIX_FMT% -s %WIDTH%x%HEIGHT% -r %FPS% -i - -vf vflip,pad=ceil(iw/2)*2:ceil(ih/2)*2 -c:v libx264 -preset ultrafast -tune zerolatency -qp 18 -pix_fmt yuv420p %NAME%_%STREAM%.mp4";

    public Capture capture = new Capture();
    public Sync sync = new Sync();
    public Ffmpeg ffmpeg = new Ffmpeg();
    public Shader shader = new Shader();
    public MotionBlur motionBlur = new MotionBlur();

    public static PerfectFrameConfig load(Path configDirectory) {
        Path configFile = configDirectory.resolve(FILE_NAME);
        try {
            Files.createDirectories(configDirectory);
            if (Files.exists(configFile)) {
                try (Reader reader = Files.newBufferedReader(configFile)) {
                    PerfectFrameConfig loaded = GSON.fromJson(reader, PerfectFrameConfig.class);
                    if (loaded != null) {
                        boolean changed = loaded.normalize();
                        if (changed) {
                            loaded.save(configDirectory);
                        }
                        return loaded;
                    }
                }
            }
        } catch (Exception exception) {
            Constants.LOG.warn("Failed to read {}, using defaults", configFile, exception);
        }

        PerfectFrameConfig defaults = new PerfectFrameConfig();
        defaults.normalize();
        defaults.save(configDirectory);
        return defaults;
    }

    public void save(Path configDirectory) {
        Path configFile = configDirectory.resolve(FILE_NAME);
        try {
            Files.createDirectories(configDirectory);
            try (Writer writer = Files.newBufferedWriter(configFile)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException exception) {
            Constants.LOG.warn("Failed to save {}", configFile, exception);
        }
    }

    public boolean normalize() {
        boolean changed = false;
        if (capture == null) capture = new Capture();
        if (sync == null) sync = new Sync();
        if (ffmpeg == null) ffmpeg = new Ffmpeg();
        if (shader == null) shader = new Shader();
        if (motionBlur == null) motionBlur = new MotionBlur();
        if (ffmpeg.mode == null) {
            ffmpeg.mode = FfmpegMode.CUSTOM_PATH;
            changed = true;
        }
        int normalizedFps = Math.max(1, capture.fps);
        if (capture.fps != normalizedFps) {
            capture.fps = normalizedFps;
            changed = true;
        }
        int normalizedFrameLimit = Math.max(-1, capture.frameLimit);
        if (capture.frameLimit != normalizedFrameLimit) {
            capture.frameLimit = normalizedFrameLimit;
            changed = true;
        }
        if (capture.resolutionMode == null) {
            capture.resolutionMode = ResolutionMode.NATIVE;
            changed = true;
        }
        double normalizedResolutionScale = Math.max(0.1D, Math.min(1.0D, capture.resolutionScale));
        if (Double.compare(capture.resolutionScale, normalizedResolutionScale) != 0) {
            capture.resolutionScale = normalizedResolutionScale;
            changed = true;
        }
        int normalizedOutputWidth = normalizeDimension(capture.outputWidth);
        if (capture.outputWidth != normalizedOutputWidth) {
            capture.outputWidth = normalizedOutputWidth;
            changed = true;
        }
        int normalizedOutputHeight = normalizeDimension(capture.outputHeight);
        if (capture.outputHeight != normalizedOutputHeight) {
            capture.outputHeight = normalizedOutputHeight;
            changed = true;
        }
        double normalizedEngineSpeed = Math.max(0.01D, sync.engineSpeed);
        if (Double.compare(sync.engineSpeed, normalizedEngineSpeed) != 0) {
            sync.engineSpeed = normalizedEngineSpeed;
            changed = true;
        }
        if (capture.outputPath == null || capture.outputPath.isBlank()) {
            capture.outputPath = "perfectflow_captures";
            changed = true;
        }
        if (ffmpeg.customPath == null) {
            ffmpeg.customPath = "";
            changed = true;
        }
        if (ffmpeg.qualityPreset == null) {
            ffmpeg.qualityPreset = QualityPreset.BALANCED;
            changed = true;
        }
        int normalizedBitrate = Math.max(250, ffmpeg.videoBitrateKbps);
        if (ffmpeg.videoBitrateKbps != normalizedBitrate) {
            ffmpeg.videoBitrateKbps = normalizedBitrate;
            changed = true;
        }
        int normalizedQueueCapacity = Math.max(1, Math.min(240, ffmpeg.writerQueueCapacityFrames));
        if (ffmpeg.writerQueueCapacityFrames != normalizedQueueCapacity) {
            ffmpeg.writerQueueCapacityFrames = normalizedQueueCapacity;
            changed = true;
        }
        int normalizedStallTimeout = Math.max(1000, ffmpeg.writerStallTimeoutMillis);
        if (ffmpeg.writerStallTimeoutMillis != normalizedStallTimeout) {
            ffmpeg.writerStallTimeoutMillis = normalizedStallTimeout;
            changed = true;
        }
        if (ffmpeg.videoArgs == null) {
            ffmpeg.videoArgs = "";
            changed = true;
        } else if (LEGACY_DEFAULT_VIDEO_ARGS.equals(ffmpeg.videoArgs) || PREVIOUS_DEFAULT_VIDEO_ARGS.equals(ffmpeg.videoArgs)) {
            ffmpeg.videoArgs = "";
            changed = true;
        }
        if (motionBlur.mode == null) {
            motionBlur.mode = MotionBlurMode.FRAME_BLEND;
            changed = true;
        }
        double normalizedShutterFraction = Math.max(0.0D, Math.min(1.0D, motionBlur.shutterFraction));
        if (Double.compare(motionBlur.shutterFraction, normalizedShutterFraction) != 0) {
            motionBlur.shutterFraction = normalizedShutterFraction;
            changed = true;
        }
        int normalizedSampleCount = Math.max(2, Math.min(16, motionBlur.sampleCount));
        if (motionBlur.sampleCount != normalizedSampleCount) {
            motionBlur.sampleCount = normalizedSampleCount;
            changed = true;
        }
        int normalizedBlendFrameCount = Math.max(2, Math.min(16, motionBlur.blendFrameCount));
        if (motionBlur.blendFrameCount != normalizedBlendFrameCount) {
            motionBlur.blendFrameCount = normalizedBlendFrameCount;
            changed = true;
        }
        return changed;
    }

    private static int normalizeDimension(int dimension) {
        if (dimension <= 0) {
            return 0;
        }
        int normalized = Math.max(2, dimension);
        return normalized % 2 == 0 ? normalized : normalized + 1;
    }

    public static class Capture {
        public int fps = 60;
        public String outputPath = "perfectflow_captures";
        public OutputMode outputMode = OutputMode.FFMPEG_MP4;
        public boolean recordColor = true;
        public boolean recordAlpha = false;
        public boolean recordDepth = false;
        public int frameLimit = -1;
        public boolean showRecordingHud = true;
        public ResolutionMode resolutionMode = ResolutionMode.NATIVE;
        public double resolutionScale = 1.0D;
        public int outputWidth = 0;
        public int outputHeight = 0;
    }

    public static class Sync {
        public boolean enabled = true;
        public double engineSpeed = 1.0D;
    }

    public static class Ffmpeg {
        public FfmpegMode mode = FfmpegMode.CUSTOM_PATH;
        public String customPath = "";
        public QualityPreset qualityPreset = QualityPreset.BALANCED;
        public int videoBitrateKbps = 8000;
        public int writerQueueCapacityFrames = 12;
        public int writerStallTimeoutMillis = 30000;
        public String videoArgs = "";
        public boolean enableLogging = true;
    }

    public static class Shader {
        public ShaderCaptureMode captureMode = ShaderCaptureMode.AUTO;
    }

    public static class MotionBlur {
        public boolean enabled = false;
        public MotionBlurMode mode = MotionBlurMode.FRAME_BLEND;
        public double shutterFraction = 0.5D;
        public int sampleCount = 4;
        public int blendFrameCount = 4;
    }

    public enum OutputMode {
        FFMPEG_MP4,
        TGA_SEQUENCE
    }

    public enum ResolutionMode {
        NATIVE,
        SCALE,
        FIXED
    }

    public enum QualityPreset {
        SMALL,
        BALANCED,
        FAST
    }

    public enum FfmpegMode {
        CUSTOM_PATH
    }

    public enum ShaderCaptureMode {
        AUTO,
        VANILLA,
        IRIS,
        OCULUS
    }

    public enum MotionBlurMode {
        ACCUMULATION,
        FRAME_BLEND
    }

    private static final class FfmpegModeAdapter implements JsonSerializer<FfmpegMode>, JsonDeserializer<FfmpegMode> {
        @Override
        public JsonElement serialize(FfmpegMode src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src == null ? FfmpegMode.CUSTOM_PATH.name() : src.name());
        }

        @Override
        public FfmpegMode deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) {
                return FfmpegMode.CUSTOM_PATH;
            }
            String value = json.getAsString();
            if (value == null || value.isBlank()) {
                return FfmpegMode.CUSTOM_PATH;
            }
            try {
                return FfmpegMode.valueOf(value);
            } catch (IllegalArgumentException exception) {
                return FfmpegMode.CUSTOM_PATH;
            }
        }
    }
}
