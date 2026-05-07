package com.perfectframe.capture.export;

import com.perfectframe.capture.CaptureSession;
import com.perfectframe.capture.ffmpeg.FfmpegLocator;
import com.perfectframe.capture.frame.CapturedFrame;
import com.perfectframe.capture.frame.PixelFormat;
import com.perfectframe.config.PerfectFrameConfig;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    private BlockingQueue<QueuedFrame> queue;
    private int queueCapacity;
    private long stallTimeoutMillis;
    private Thread writerThread;
    private volatile Exception writerFailure;
    private volatile boolean closing;

    @Override
    public void open(CaptureSession session, String streamName, int width, int height, PixelFormat format) throws Exception {
        this.session = session;
        PerfectFrameConfig config = session.config();
        Files.createDirectories(session.outputDirectory());
        Path ffmpeg = FfmpegLocator.locate(config);

        List<String> command = new ArrayList<>();
        command.add(ffmpeg.toString());
        command.addAll(buildArguments(session, streamName, width, height, format));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(session.outputDirectory().toFile());
        builder.redirectErrorStream(true);
        if (config.ffmpeg.enableLogging) {
            logFile = session.outputDirectory().resolve(session.name() + "_" + streamName + ".ffmpeg.log");
            builder.redirectOutput(logFile.toFile());
        } else {
            logFile = null;
        }

        process = builder.start();
        OutputStream outputStream = process.getOutputStream();
        pipe = Channels.newChannel(outputStream);
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
        throwIfWriterFailed();
    }

    private List<String> buildArguments(CaptureSession session, String streamName, int width, int height, PixelFormat format) {
        PerfectFrameConfig config = session.config();
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
        String filter = "vflip,scale=" + outputWidth + ":" + outputHeight + ":flags=bicubic,pad=ceil(iw/2)*2:ceil(ih/2)*2";

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
        args.add(session.name() + "_" + streamName + ".mp4");
        return args;
    }

    private int outputWidth(PerfectFrameConfig config, int width) {
        return switch (config.capture.resolutionMode) {
            case SCALE -> evenDimension(Math.max(2, (int) Math.round(width * config.capture.resolutionScale)));
            case FIXED -> config.capture.outputWidth > 0 ? config.capture.outputWidth : evenDimension(width);
            case NATIVE -> evenDimension(width);
        };
    }

    private int outputHeight(PerfectFrameConfig config, int height) {
        return switch (config.capture.resolutionMode) {
            case SCALE -> evenDimension(Math.max(2, (int) Math.round(height * config.capture.resolutionScale)));
            case FIXED -> config.capture.outputHeight > 0 ? config.capture.outputHeight : evenDimension(height);
            case NATIVE -> evenDimension(height);
        };
    }

    private int evenDimension(int dimension) {
        return dimension % 2 == 0 ? dimension : dimension + 1;
    }

    private String presetArgument(PerfectFrameConfig.QualityPreset preset) {
        return switch (preset) {
            case SMALL -> "medium";
            case BALANCED -> "veryfast";
            case FAST -> "ultrafast";
        };
    }

    private int effectiveBitrate(int configuredBitrate, PerfectFrameConfig.QualityPreset preset) {
        return switch (preset) {
            case SMALL -> Math.max(250, configuredBitrate / 2);
            case BALANCED -> configuredBitrate;
            case FAST -> Math.max(250, configuredBitrate);
        };
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
                    writeFully(queuedFrame.frame().pixels());
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
