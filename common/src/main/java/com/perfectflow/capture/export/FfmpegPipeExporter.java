package com.perfectflow.capture.export;

import com.perfectflow.audio.SystemAudioMetadata;
import com.perfectflow.capture.CaptureSession;
import com.perfectflow.capture.ffmpeg.FfmpegLocator;
import com.perfectflow.capture.frame.CapturedFrame;
import com.perfectflow.capture.frame.PixelFormat;
import com.perfectflow.config.PerfectFlowConfig;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public final class FfmpegPipeExporter implements FrameExporter {
    private static final long ENQUEUE_POLL_MILLIS = 100L;
    private static final QueuedFrame POISON = new QueuedFrame(null, true);

    private Process process;
    private WritableByteChannel pipe;
    private Path logFile;
    private CaptureSession session;
    private MotionBlurFrameProcessor motionBlurProcessor;
    private BlockingQueue<QueuedFrame> queue;
    private int queueCapacity;
    private long stallTimeoutMillis;
    private Thread writerThread;
    private volatile Exception writerFailure;
    private volatile boolean closing;
    private Path outputFile;
    private Path tempVideoFile;
    private Path tempAudioFile;
    private Path tempAudioMetadataFile;
    private Path tempAudioAnchorFile;
    private boolean audioRequested;

    @Override
    public void open(CaptureSession session, String streamName, int width, int height, PixelFormat format) throws Exception {
        this.session = session;
        PerfectFlowConfig config = session.config();
        Files.createDirectories(session.outputDirectory());
        Path ffmpeg = FfmpegLocator.locate(config);
        outputFile = session.outputDirectory().resolve(session.name() + "_" + streamName + ".mp4");
        audioRequested = shouldUseAudio(session, streamName, format);
        tempVideoFile = audioRequested ? session.outputDirectory().resolve(session.name() + "_" + streamName + ".video.tmp.mp4") : outputFile;
        tempAudioFile = audioRequested ? session.audioTempFile() : null;
        tempAudioMetadataFile = audioRequested ? session.audioMetadataFile() : null;
        tempAudioAnchorFile = audioRequested ? session.audioAnchorFile() : null;

        List<String> command = new ArrayList<>();
        command.add(ffmpeg.toString());
        command.addAll(buildArguments(session, streamName, width, height, format, tempVideoFile));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(session.outputDirectory().toFile());
        builder.redirectErrorStream(true);
        if (config.ffmpeg.enableLogging) {
            logFile = session.outputDirectory().resolve(session.name() + "_" + streamName + ".ffmpeg.log");
            builder.redirectOutput(logFile.toFile());
        } else {
            logFile = null;
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }

        process = builder.start();
        OutputStream outputStream = process.getOutputStream();
        pipe = Channels.newChannel(outputStream);
        motionBlurProcessor = shouldUseExporterMotionBlur(config, streamName) ? new MotionBlurFrameProcessor(config, streamName) : null;
        queueCapacity = config.ffmpeg.writerQueueCapacityFrames;
        stallTimeoutMillis = config.ffmpeg.writerStallTimeoutMillis;
        queue = new ArrayBlockingQueue<>(queueCapacity);
        session.setExporterQueueStatus(0, queueCapacity);
        writerThread = new Thread(this::writeQueuedFrames, "PerfectFlow FFmpeg Writer " + streamName);
        writerThread.setDaemon(true);
        writerThread.start();
    }

    @Override
    public void export(CapturedFrame frame) throws Exception {
        throwIfWriterFailed();
        if (closing) {
            throw new IllegalStateException("FFmpeg writer is already closing");
        }
        enqueueFrame(frame);
    }

    @Override
    public void close() throws Exception {
        closing = true;
        if (queue != null) {
            while (true) {
                if (queue.offer(POISON, 100, TimeUnit.MILLISECONDS)) {
                    break;
                }
                throwIfWriterFailed();
                if (writerThread != null && !writerThread.isAlive()) {
                    break;
                }
            }
        }
        if (writerThread != null) {
            writerThread.join(TimeUnit.SECONDS.toMillis(10));
            if (writerThread.isAlive()) {
                writerThread.interrupt();
                throw new IllegalStateException("FFmpeg writer did not stop cleanly");
            }
        } else if (pipe != null) {
            pipe.close();
        }
        if (process != null) {
            int code = process.waitFor();
            if (code != 0) {
                String message = "FFmpeg exited with code " + code;
                if (logFile != null) {
                    message += " (see " + logFile + ")";
                }
                throw new IllegalStateException(message);
            }
        }
        if (audioRequested && hasUsableAudioCapture()) {
            try {
                remuxAudioIntoFinalFile();
            } catch (Exception exception) {
                moveTempVideoToFinal();
            } finally {
                cleanupTempAudio();
            }
        } else if (audioRequested) {
            moveTempVideoToFinal();
            cleanupTempAudio();
        } else if (!tempVideoFile.equals(outputFile)) {
            moveTempVideoToFinal();
        }
        throwIfWriterFailed();
    }

    private List<String> buildArguments(CaptureSession session, String streamName, int width, int height, PixelFormat format, Path targetFile) {
        PerfectFlowConfig config = session.config();
        if (config.ffmpeg.videoArgs != null && !config.ffmpeg.videoArgs.isBlank()) {
            String args = config.ffmpeg.videoArgs
                    .replace("%PIX_FMT%", format.ffmpegName())
                    .replace("%WIDTH%", Integer.toString(width))
                    .replace("%HEIGHT%", Integer.toString(height))
                    .replace("%FPS%", Integer.toString(config.capture.fps))
                    .replace("%NAME%", session.name())
                    .replace("%STREAM%", streamName);
            return splitCommandLine(args);
        }

        int outputWidth = outputWidth(config, width);
        int outputHeight = outputHeight(config, height);
        session.setOutputSize(outputWidth, outputHeight);
        int effectiveBitrate = effectiveBitrate(config.ffmpeg.videoBitrateKbps, config.ffmpeg.qualityPreset);
        String bitrate = effectiveBitrate + "k";
        String filter = buildVideoFilter(config, streamName, outputWidth, outputHeight);

        List<String> args = new ArrayList<>();
        args.add("-y");
        args.add("-f");
        args.add("rawvideo");
        args.add("-pix_fmt");
        args.add(format.ffmpegName());
        args.add("-s");
        args.add(width + "x" + height);
        args.add("-r");
        args.add(Integer.toString(config.capture.fps));
        args.add("-i");
        args.add("-");
        args.add("-vf");
        args.add(filter);
        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add(presetArgument(config.ffmpeg.qualityPreset));
        args.add("-tune");
        args.add("zerolatency");
        args.add("-bf");
        args.add("0");
        args.add("-b:v");
        args.add(bitrate);
        args.add("-maxrate");
        args.add(bitrate);
        args.add("-bufsize");
        args.add((effectiveBitrate * 2) + "k");
        args.add("-pix_fmt");
        args.add("yuv420p");
        args.add("-movflags");
        args.add("+faststart");
        args.add(targetFile.toString());
        return args;
    }

    private int outputWidth(PerfectFlowConfig config, int width) {
        return switch (config.capture.resolutionMode) {
            case SCALE -> evenDimension(Math.max(2, (int) Math.round(width * config.capture.resolutionScale)));
            case FIXED -> config.capture.outputWidth > 0 ? config.capture.outputWidth : evenDimension(width);
            case NATIVE -> evenDimension(width);
        };
    }

    private int outputHeight(PerfectFlowConfig config, int height) {
        return switch (config.capture.resolutionMode) {
            case SCALE -> evenDimension(Math.max(2, (int) Math.round(height * config.capture.resolutionScale)));
            case FIXED -> config.capture.outputHeight > 0 ? config.capture.outputHeight : evenDimension(height);
            case NATIVE -> evenDimension(height);
        };
    }

    private int evenDimension(int dimension) {
        return dimension % 2 == 0 ? dimension : dimension + 1;
    }

    private String presetArgument(PerfectFlowConfig.QualityPreset preset) {
        return switch (preset) {
            case SMALL -> "medium";
            case BALANCED -> "veryfast";
            case FAST -> "ultrafast";
        };
    }

    private int effectiveBitrate(int configuredBitrate, PerfectFlowConfig.QualityPreset preset) {
        return switch (preset) {
            case SMALL -> Math.max(250, configuredBitrate / 2);
            case BALANCED -> configuredBitrate;
            case FAST -> Math.max(250, configuredBitrate);
        };
    }

    private boolean shouldUseExporterMotionBlur(PerfectFlowConfig config, String streamName) {
        return config.motionBlur != null
                && config.motionBlur.enabled
                && config.motionBlur.path == PerfectFlowConfig.MotionBlurPath.EXPORTER_THREAD
                && "color".equals(streamName);
    }

    private boolean shouldUseAudio(CaptureSession session, String streamName, PixelFormat format) {
        PerfectFlowConfig config = session.config();
        if (!session.effectiveAudioEnabled()) {
            return false;
        }
        if (!"color".equals(streamName)) {
            return false;
        }
        if (format != PixelFormat.BGR24 && format != PixelFormat.BGRA32) {
            return false;
        }
        if (config.capture.outputMode != PerfectFlowConfig.OutputMode.FFMPEG_MP4) {
            return false;
        }
        if (config.ffmpeg.videoArgs != null && !config.ffmpeg.videoArgs.isBlank()) {
            return false;
        }
        return true;
    }

    private String buildVideoFilter(PerfectFlowConfig config, String streamName, int outputWidth, int outputHeight) {
        List<String> filters = new ArrayList<>();
        filters.add("vflip");
        if (config.motionBlur != null
                && config.motionBlur.enabled
                && config.motionBlur.path == PerfectFlowConfig.MotionBlurPath.FFMPEG_FILTER
                && "color".equals(streamName)) {
            filters.add(buildTmixFilter(config.motionBlur));
        }
        filters.add("scale=" + outputWidth + ":" + outputHeight + ":flags=bicubic");
        filters.add("pad=ceil(iw/2)*2:ceil(ih/2)*2");
        return String.join(",", filters);
    }

    private String buildTmixFilter(PerfectFlowConfig.MotionBlur motionBlur) {
        int frames;
        double[] weights;
        if (motionBlur.mode == PerfectFlowConfig.MotionBlurMode.ACCUMULATION) {
            frames = Math.max(2, Math.min(16, motionBlur.sampleCount));
            weights = buildAccumulationWeights(frames, motionBlur.shutterFraction);
        } else {
            frames = Math.max(2, Math.min(16, motionBlur.blendFrameCount));
            weights = buildFrameBlendWeights(frames, motionBlur.shutterFraction);
        }

        StringBuilder filter = new StringBuilder("tmix=frames=").append(frames).append(":weights=");
        for (int i = 0; i < weights.length; i++) {
            if (i > 0) {
                filter.append(' ');
            }
            filter.append(String.format(Locale.ROOT, "%.6f", weights[i]));
        }
        return filter.toString();
    }

    private double[] buildFrameBlendWeights(int frames, double strength) {
        double[] weights = new double[frames];
        double clampedStrength = Math.max(0.0D, Math.min(1.0D, strength));
        for (int i = 0; i < frames; i++) {
            double t = frames <= 1 ? 0.0D : i / (double) (frames - 1);
            weights[i] = 1.0D - clampedStrength + (clampedStrength * (t + 1.0D / frames));
        }
        return weights;
    }

    private double[] buildAccumulationWeights(int frames, double shutterFraction) {
        double[] weights = new double[frames];
        double alpha = Math.max(0.02D, Math.min(1.0D, shutterFraction / Math.max(1, frames)));
        double carry = 1.0D;
        for (int i = frames - 1; i >= 0; i--) {
            weights[i] = alpha * carry;
            carry *= (1.0D - alpha);
        }
        return weights;
    }

    private void enqueueFrame(CapturedFrame frame) throws Exception {
        QueuedFrame queuedFrame = new QueuedFrame(frame, false);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stallTimeoutMillis);
        while (!queue.offer(queuedFrame, ENQUEUE_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            throwIfWriterFailed();
            session.setExporterQueueStatus(queue.size(), queueCapacity);
            if (writerThread != null && !writerThread.isAlive()) {
                throw new IllegalStateException("FFmpeg writer stopped while frames were still queued");
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("FFmpeg could not keep up for " + stallTimeoutMillis + "ms. Lower capture FPS, output resolution, bitrate, or use the FAST quality preset.");
            }
        }
        session.setExporterQueueStatus(queue.size(), queueCapacity);
    }

    private void writeQueuedFrames() {
        try {
            while (true) {
                QueuedFrame queuedFrame = queue.take();
                if (queuedFrame.poison()) {
                    break;
                }
                try {
                    ByteBuffer pixels = motionBlurProcessor == null
                            ? queuedFrame.frame().pixels()
                            : motionBlurProcessor.process(queuedFrame.frame());
                    writeFully(pixels);
                    session.setExporterQueueStatus(queue.size(), queueCapacity);
                } finally {
                    queuedFrame.frame().release();
                }
            }
        } catch (Exception exception) {
            writerFailure = exception;
        } finally {
            try {
                if (pipe != null) {
                    pipe.close();
                }
            } catch (Exception exception) {
                if (writerFailure == null) {
                    writerFailure = exception;
                }
            }
        }
    }

    private void remuxAudioIntoFinalFile() throws Exception {
        SystemAudioMetadata metadata = loadAudioMetadata();
        Path ffmpeg = FfmpegLocator.locate(session.config());
        AudioSyncPlan syncPlan = buildAudioSyncPlan(metadata);
        String audioFilter = buildAudioFilter(syncPlan);
        List<String> command = new ArrayList<>();
        command.add(ffmpeg.toString());
        command.add("-y");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add(session.config().ffmpeg.enableLogging ? "info" : "error");
        command.add("-i");
        command.add(tempVideoFile.toString());
        command.add("-f");
        command.add(metadata.sampleFormat());
        command.add("-ar");
        command.add(Integer.toString(metadata.sampleRate()));
        command.add("-ac");
        command.add(Integer.toString(metadata.channels()));
        command.add("-i");
        command.add(tempAudioFile.toString());
        command.add("-map");
        command.add("0:v:0");
        command.add("-map");
        command.add("1:a:0");
        command.add("-c:v");
        command.add("copy");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");
        command.add("-af");
        command.add(audioFilter);
        command.add("-movflags");
        command.add("+faststart");
        command.add(outputFile.toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(session.outputDirectory().toFile());
        Path remuxLog = session.outputDirectory().resolve(session.name() + "_color.remux.ffmpeg.log");
        if (session.config().ffmpeg.enableLogging) {
            builder.redirectErrorStream(true);
            builder.redirectOutput(remuxLog.toFile());
        } else {
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        Process remux = builder.start();
        int code = remux.waitFor();
        if (code != 0) {
            throw new IllegalStateException("Audio mux ffmpeg exited with code " + code);
        }
        Files.deleteIfExists(tempVideoFile);
    }

    private void moveTempVideoToFinal() throws Exception {
        if (tempVideoFile == null || outputFile == null || tempVideoFile.equals(outputFile)) {
            return;
        }
        Files.move(tempVideoFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private void cleanupTempAudio() {
        if (tempAudioFile != null) {
            try {
                Files.deleteIfExists(tempAudioFile);
            } catch (Exception ignored) {
            }
        }
        if (tempAudioMetadataFile != null) {
            try {
                Files.deleteIfExists(tempAudioMetadataFile);
            } catch (Exception ignored) {
            }
        }
        if (tempAudioAnchorFile != null) {
            try {
                Files.deleteIfExists(tempAudioAnchorFile);
            } catch (Exception ignored) {
            }
        }
    }

    private boolean hasUsableAudioCapture() throws Exception {
        if (tempAudioFile == null || tempAudioMetadataFile == null) {
            return false;
        }
        if (!Files.exists(tempAudioFile) || !Files.exists(tempAudioMetadataFile)) {
            return false;
        }
        if (Files.size(tempAudioFile) <= 0L) {
            return false;
        }
        return loadAudioMetadata().totalFrames() > 0L;
    }

    private SystemAudioMetadata loadAudioMetadata() throws Exception {
        if (tempAudioMetadataFile == null) {
            throw new IllegalStateException("Audio metadata file is unavailable.");
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(tempAudioMetadataFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return SystemAudioMetadata.fromProperties(properties);
    }

    private AudioSyncPlan buildAudioSyncPlan(SystemAudioMetadata metadata) {
        double videoDurationSeconds = session.capturedFrames() / (double) Math.max(1, session.scheduler().targetFps());
        List<AudioSyncAnchor> anchors;
        try {
            anchors = loadAudioAnchors();
        } catch (Exception exception) {
            session.markPreciseAudioSyncUnavailable("Failed to read audio sync anchors: " + exception.getMessage());
            return AudioSyncPlan.basic(videoDurationSeconds);
        }
        if (anchors.size() < 2) {
            session.markPreciseAudioSyncUnavailable("Audio sync anchors were missing or incomplete.");
            return AudioSyncPlan.basic(videoDurationSeconds);
        }
        double fps = Math.max(1, session.scheduler().targetFps());
        double sampleRate = Math.max(1, metadata.sampleRate());
        RegressionFit fit = RegressionFit.fromAnchors(anchors, fps, sampleRate);
        if (!fit.valid()) {
            session.markPreciseAudioSyncUnavailable("Audio sync anchors could not produce a stable timing fit.");
            return AudioSyncPlan.basic(videoDurationSeconds);
        }

        double trimStartSeconds = Math.max(0.0D, fit.interceptSeconds());
        long delayMillis = fit.interceptSeconds() < 0.0D
                ? Math.max(0L, Math.round(-fit.interceptSeconds() * 1000.0D))
                : 0L;
        double tempoFactor = fit.slope();
        if (!Double.isFinite(tempoFactor) || tempoFactor <= 0.0D || tempoFactor < 0.5D || tempoFactor > 2.0D) {
            session.markPreciseAudioSyncUnavailable("Audio sync drift fit was outside a safe range.");
            return AudioSyncPlan.basic(videoDurationSeconds);
        }
        return new AudioSyncPlan(videoDurationSeconds, trimStartSeconds, delayMillis, tempoFactor, true);
    }

    private String buildAudioFilter(AudioSyncPlan plan) {
        List<String> filters = new ArrayList<>();
        if (plan.trimStartSeconds() > 0.000_001D) {
            filters.add("atrim=start=" + formatDecimal(plan.trimStartSeconds()));
            filters.add("asetpts=PTS-STARTPTS");
        }
        if (Math.abs(plan.tempoFactor() - 1.0D) > 0.000_5D) {
            filters.addAll(buildAtempoFilters(plan.tempoFactor()));
        }
        filters.add("aresample=async=1:first_pts=0");
        if (plan.delayMillis() > 0L) {
            filters.add("adelay=" + plan.delayMillis() + ":all=1");
        }
        filters.add("apad");
        filters.add("atrim=0:" + formatDecimal(plan.videoDurationSeconds()));
        return String.join(",", filters);
    }

    private List<String> buildAtempoFilters(double tempoFactor) {
        List<String> filters = new ArrayList<>();
        double remaining = tempoFactor;
        while (remaining > 2.0D) {
            filters.add("atempo=2.0");
            remaining /= 2.0D;
        }
        while (remaining < 0.5D) {
            filters.add("atempo=0.5");
            remaining /= 0.5D;
        }
        filters.add("atempo=" + formatDecimal(remaining));
        return filters;
    }

    private List<AudioSyncAnchor> loadAudioAnchors() throws Exception {
        if (tempAudioAnchorFile == null || !Files.exists(tempAudioAnchorFile)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(tempAudioAnchorFile, StandardCharsets.UTF_8);
        List<AudioSyncAnchor> anchors = new ArrayList<>();
        long lastVideoFrames = -1L;
        long lastAudioFrames = -1L;
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("videoFramesCompleted")) {
                continue;
            }
            String[] parts = trimmed.split(",", 2);
            if (parts.length < 2) {
                continue;
            }
            long videoFrames = Long.parseLong(parts[0].trim());
            long audioFrames = Long.parseLong(parts[1].trim());
            if (videoFrames <= lastVideoFrames || audioFrames < lastAudioFrames) {
                continue;
            }
            anchors.add(new AudioSyncAnchor(videoFrames, audioFrames));
            lastVideoFrames = videoFrames;
            lastAudioFrames = audioFrames;
        }
        return anchors;
    }

    private String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private void writeFully(ByteBuffer pixels) throws Exception {
        while (pixels.hasRemaining()) {
            pipe.write(pixels);
        }
    }

    private void throwIfWriterFailed() throws Exception {
        if (writerFailure != null) {
            throw writerFailure;
        }
    }

    private static List<String> splitCommandLine(String commandLine) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    private record QueuedFrame(CapturedFrame frame, boolean poison) {
    }

    private record AudioSyncAnchor(long videoFramesCompleted, long audioFramesCaptured) {
    }

    private record AudioSyncPlan(
            double videoDurationSeconds,
            double trimStartSeconds,
            long delayMillis,
            double tempoFactor,
            boolean precise
    ) {
        private static AudioSyncPlan basic(double videoDurationSeconds) {
            return new AudioSyncPlan(videoDurationSeconds, 0.0D, 0L, 1.0D, false);
        }
    }

    private record RegressionFit(double interceptSeconds, double slope, boolean valid) {
        private static RegressionFit fromAnchors(List<AudioSyncAnchor> anchors, double fps, double sampleRate) {
            int count = anchors.size();
            if (count < 2) {
                return new RegressionFit(0.0D, 1.0D, false);
            }
            double sumX = 0.0D;
            double sumY = 0.0D;
            double sumXX = 0.0D;
            double sumXY = 0.0D;
            for (AudioSyncAnchor anchor : anchors) {
                double x = anchor.videoFramesCompleted() / fps;
                double y = anchor.audioFramesCaptured() / sampleRate;
                sumX += x;
                sumY += y;
                sumXX += x * x;
                sumXY += x * y;
            }
            double denominator = (count * sumXX) - (sumX * sumX);
            if (Math.abs(denominator) < 1.0E-9D) {
                return new RegressionFit(0.0D, 1.0D, false);
            }
            double slope = ((count * sumXY) - (sumX * sumY)) / denominator;
            double intercept = (sumY - (slope * sumX)) / count;
            if (!Double.isFinite(slope) || !Double.isFinite(intercept)) {
                return new RegressionFit(0.0D, 1.0D, false);
            }
            return new RegressionFit(intercept, slope, true);
        }
    }
}
