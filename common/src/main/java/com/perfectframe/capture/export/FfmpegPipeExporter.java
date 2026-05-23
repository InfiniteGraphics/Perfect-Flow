package com.perfectframe.capture.export;

import com.perfectframe.capture.CaptureSession;
import com.perfectframe.capture.ffmpeg.FfmpegLocator;
import com.perfectframe.capture.frame.CapturedFrame;
import com.perfectframe.capture.frame.PixelFormat;
import com.perfectframe.config.PerfectFlowConfig;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public final class FfmpegPipeExporter implements FrameExporter {
    private static final long ENQUEUE_POLL_MILLIS = 100L;
    private static final QueuedFrame POISON = new QueuedFrame(null, true);
    private static final Pattern QUOTED_VALUE_PATTERN = Pattern.compile("\"([^\"]+)\"");
    private static final List<String> AUDIO_OUTPUT_HINTS = List.of(
            "stereo mix",
            "what u hear",
            "wave out mix",
            "monitor",
            "vb-cable",
            "cable output",
            "loopback"
    );

    private Process process;
    private Process audioProcess;
    private WritableByteChannel pipe;
    private Path logFile;
    private Path audioLogFile;
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
    private boolean audioRequested;
    private boolean audioActive;
    private boolean audioSupported;

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
        if (audioRequested && tempAudioFile != null && Files.exists(tempAudioFile) && Files.size(tempAudioFile) > 44L) {
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

    private void updateAudioHealth() {
        if (!audioActive || audioProcess == null) {
            return;
        }
        if (!audioProcess.isAlive()) {
            audioActive = false;
            audioSupported = false;
            session.setAudioStatus(false, true, "Audio capture stopped early; saved video-only output.");
        }
    }

    private void startAudioCapture(PerfectFlowConfig config) throws Exception {
        if (tempAudioFile == null) {
            throw new IllegalStateException("Audio temp file is unavailable.");
        }
        Path ffmpeg = FfmpegLocator.locate(config);
        String audioInput = resolveDshowAudioInput(config, ffmpeg);
        List<String> command = new ArrayList<>();
        command.add(ffmpeg.toString());
        command.add("-y");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add(config.ffmpeg.enableLogging ? "info" : "error");
        command.add("-thread_queue_size");
        command.add("4096");
        command.add("-f");
        command.add("dshow");
        command.add("-sample_rate");
        command.add("48000");
        command.add("-channels");
        command.add("2");
        command.add("-i");
        command.add(audioInput);
        command.add("-c:a");
        command.add("pcm_s16le");
        command.add(tempAudioFile.toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(session.outputDirectory().toFile());
        if (config.ffmpeg.enableLogging) {
            audioLogFile = session.outputDirectory().resolve(session.name() + "_color.audio.ffmpeg.log");
            builder.redirectErrorStream(true);
            builder.redirectOutput(audioLogFile.toFile());
        } else {
            audioLogFile = null;
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        audioProcess = builder.start();
    }

    private void finishAudioCapture() {
        if (audioProcess == null) {
            return;
        }
        if (audioProcess.isAlive()) {
            try {
                OutputStream stdin = audioProcess.getOutputStream();
                stdin.write('q');
                stdin.write('\n');
                stdin.flush();
                stdin.close();
            } catch (Exception ignored) {
                audioProcess.destroy();
            }
        }
        try {
            audioProcess.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (audioProcess.isAlive()) {
            audioProcess.destroyForcibly();
        }
        audioProcess = null;
    }

    private void remuxAudioIntoFinalFile() throws Exception {
        Path ffmpeg = FfmpegLocator.locate(session.config());
        List<String> command = new ArrayList<>();
        command.add(ffmpeg.toString());
        command.add("-y");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add(session.config().ffmpeg.enableLogging ? "info" : "error");
        command.add("-i");
        command.add(tempVideoFile.toString());
        command.add("-i");
        command.add(tempAudioFile.toString());
        command.add("-c:v");
        command.add("copy");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");
        command.add("-shortest");
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
    }

    private String resolveDshowAudioInput(PerfectFlowConfig config, Path ffmpeg) throws Exception {
        List<AudioDeviceCandidate> devices = listDshowAudioDevices(ffmpeg);
        if (devices.isEmpty()) {
            throw new IllegalStateException("No DirectShow audio capture devices were found.");
        }

        PerfectFlowConfig.Audio audio = config.audio;
        if (audio != null && audio.deviceSelection == PerfectFlowConfig.AudioDeviceSelection.CUSTOM) {
            String requested = audio.deviceName == null ? "" : audio.deviceName.trim();
            if (requested.isBlank()) {
                throw new IllegalStateException("Custom audio device name is empty.");
            }
            for (AudioDeviceCandidate device : devices) {
                if (device.matches(requested)) {
                    return requested;
                }
            }
            throw new IllegalStateException("Requested DirectShow audio device was not found: " + requested);
        }

        for (String hint : AUDIO_OUTPUT_HINTS) {
            for (AudioDeviceCandidate device : devices) {
                if (device.matchesHint(hint)) {
                    return device.primaryName();
                }
            }
        }

        throw new IllegalStateException("No DirectShow output-capture device was found. Install or enable Stereo Mix, Wave Out Mix, Monitor, or a virtual loopback device.");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private List<AudioDeviceCandidate> listDshowAudioDevices(Path ffmpeg) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(ffmpeg.toString());
        command.add("-hide_banner");
        command.add("-list_devices");
        command.add("true");
        command.add("-f");
        command.add("dshow");
        command.add("-i");
        command.add("dummy");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        byte[] outputBytes;
        try (var input = process.getInputStream()) {
            outputBytes = input.readAllBytes();
        }
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        String output = new String(outputBytes, StandardCharsets.UTF_8);
        return parseDshowAudioDevices(output);
    }

    private List<AudioDeviceCandidate> parseDshowAudioDevices(String output) {
        Map<String, AudioDeviceCandidate> devicesByName = new LinkedHashMap<>();
        AudioDeviceCandidate current = null;
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.contains("(audio)") || line.contains("(video)")) {
                String quoted = firstQuotedValue(line);
                if (quoted != null) {
                    if (line.contains("(audio)")) {
                        current = devicesByName.computeIfAbsent(quoted, AudioDeviceCandidate::new);
                    } else {
                        current = null;
                    }
                }
                continue;
            }

            if (current != null && line.toLowerCase(Locale.ROOT).contains("alternative name")) {
                String quoted = firstQuotedValue(line);
                if (quoted != null) {
                    current.addAlias(quoted);
                }
            }
        }
        return new ArrayList<>(devicesByName.values());
    }

    private String firstQuotedValue(String line) {
        Matcher matcher = QUOTED_VALUE_PATTERN.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static final class AudioDeviceCandidate {
        private final String primaryName;
        private final Set<String> aliases = new LinkedHashSet<>();

        private AudioDeviceCandidate(String primaryName) {
            this.primaryName = primaryName;
        }

        private String primaryName() {
            return primaryName;
        }

        private void addAlias(String alias) {
            if (alias != null && !alias.isBlank()) {
                aliases.add(alias);
            }
        }

        private boolean matches(String value) {
            if (value == null || value.isBlank()) {
                return false;
            }
            if (primaryName.equalsIgnoreCase(value)) {
                return true;
            }
            for (String alias : aliases) {
                if (alias.equalsIgnoreCase(value)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesHint(String hint) {
            String lowerHint = hint.toLowerCase(Locale.ROOT);
            if (primaryName.toLowerCase(Locale.ROOT).contains(lowerHint)) {
                return true;
            }
            for (String alias : aliases) {
                if (alias.toLowerCase(Locale.ROOT).contains(lowerHint)) {
                    return true;
                }
            }
            return false;
        }
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
}
