package com.perfectframe.capture;

import com.perfectframe.Constants;
import com.perfectframe.capture.export.FfmpegPipeExporter;
import com.perfectframe.capture.export.FrameExporter;
import com.perfectframe.capture.export.TgaSequenceExporter;
import com.perfectframe.capture.frame.CapturedFrame;
import com.perfectframe.config.PerfectFrameConfig;
import com.perfectframe.platform.Services;
import com.perfectframe.shader.CaptureSource;
import com.perfectframe.shader.ShaderPipelineAdapter;
import com.perfectframe.shader.ShaderPipelineAdapters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public enum CaptureController {
    INSTANCE;

    private final Map<String, FrameExporter> exporters = new LinkedHashMap<>();
    private PerfectFrameConfig config = new PerfectFrameConfig();
    private CaptureState state = CaptureState.IDLE;
    private CaptureSession session;
    private ShaderPipelineAdapter shaderAdapter;
    private CaptureSource captureSource;
    private boolean keyToggleQueued;

    public void configure(PerfectFrameConfig config) {
        this.config = config;
    }

    public CaptureState state() {
        return state;
    }

    public CaptureSession session() {
        return session;
    }

    public boolean isRecording() {
        return state == CaptureState.RECORDING;
    }

    public void toggle() {
        keyToggleQueued = true;
    }

    public void afterClientTick() {
        if (keyToggleQueued) {
            keyToggleQueued = false;
            if (state == CaptureState.IDLE) {
                start();
            } else if (state == CaptureState.RECORDING) {
                stop();
            }
        }
    }

    public void start() {
        if (state != CaptureState.IDLE) {
            return;
        }
        state = CaptureState.STARTING;
        try {
            Path outputRoot = Services.PLATFORM.clientAccess().gameDirectory().resolve(config.capture.outputPath);
            Files.createDirectories(outputRoot);
            session = new CaptureSession(config, outputRoot);
            shaderAdapter = ShaderPipelineAdapters.select(config);
            captureSource = shaderAdapter.resolve();
            validateCaptureSource(captureSource);
            if (config.shader.captureMode == PerfectFrameConfig.ShaderCaptureMode.OCULUS
                    && Services.PLATFORM.normalizeShaderCaptureMode(config.shader.captureMode) == PerfectFrameConfig.ShaderCaptureMode.IRIS) {
                notifyClient(Constants.MOD_NAME + ": OCULUS mode maps to IRIS on Fabric 1.20.4.");
            }
            exporters.clear();
            state = CaptureState.RECORDING;
            Constants.LOG.info("{} capture source: {}", Constants.MOD_NAME, captureSource.label());
            notifyClient(Constants.MOD_NAME + " recording started (" + captureSource.label() + ").");
        } catch (Exception exception) {
            Constants.LOG.error("Failed to start capture", exception);
            notifyClient(Constants.MOD_NAME + " failed to start: " + exception.getMessage());
            closeExporters();
            session = null;
            captureSource = null;
            state = CaptureState.IDLE;
        }
    }

    public void captureRenderedFrame() {
        if (state != CaptureState.RECORDING || session == null || session.reachedFrameLimit()) {
            if (session != null && session.reachedFrameLimit()) {
                stop();
            }
            return;
        }
        try {
            if (!Services.PLATFORM.clientAccess().isWorldReady()) {
                return;
            }
            captureSource = shaderAdapter.resolve();
            validateCaptureSource(captureSource);
            List<CapturedFrame> frames = Services.PLATFORM.clientAccess().captureFrames(session, captureSource);
            for (CapturedFrame frame : frames) {
                boolean exported = false;
                try {
                    exporterFor(frame).export(frame);
                    exported = true;
                } finally {
                    if (!exported) {
                        frame.release();
                    }
                }
            }
            if (!frames.isEmpty()) {
                session.advanceFrame();
            }
        } catch (Exception exception) {
            Constants.LOG.error("Capture failed", exception);
            notifyClient(buildFailureMessage(exception));
            stop();
        }
    }

    public void stop() {
        if (state != CaptureState.RECORDING) {
            return;
        }
        state = CaptureState.STOPPING;
        long frames = session == null ? 0 : session.capturedFrames();
        closeExporters();
        session = null;
        captureSource = null;
        state = CaptureState.IDLE;
        notifyClient(Constants.MOD_NAME + " recording stopped (" + frames + " frames).");
    }

    private void validateCaptureSource(CaptureSource source) {
        boolean needsColor = config.capture.recordColor || config.capture.recordAlpha;
        if (needsColor && !source.hasColor()) {
            throw new IllegalStateException(source.colorUnavailableReason());
        }
        if (config.capture.recordDepth && !source.hasDepth()) {
            throw new IllegalStateException(source.depthUnavailableReason());
        }
        if (!needsColor && !config.capture.recordDepth) {
            throw new IllegalStateException("No capture streams are enabled.");
        }
    }

    private FrameExporter exporterFor(CapturedFrame frame) throws Exception {
        FrameExporter existing = exporters.get(frame.streamName());
        if (existing != null) {
            return existing;
        }
        FrameExporter created = config.capture.outputMode == PerfectFrameConfig.OutputMode.TGA_SEQUENCE
                ? new TgaSequenceExporter()
                : new FfmpegPipeExporter();
        created.open(session, frame.streamName(), frame.width(), frame.height(), frame.format());
        exporters.put(frame.streamName(), created);
        return created;
    }

    private void closeExporters() {
        for (FrameExporter exporter : exporters.values()) {
            try {
                exporter.close();
            } catch (Exception exception) {
                Constants.LOG.warn("Exporter close failed", exception);
            }
        }
        exporters.clear();
    }

    private void notifyClient(String message) {
        Services.PLATFORM.clientAccess().postChatMessage(message);
    }

    private String buildFailureMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }

        if (config.capture.outputMode == PerfectFrameConfig.OutputMode.FFMPEG_MP4 && session != null) {
            String lower = message.toLowerCase();
            if (lower.contains("pipe") || lower.contains("ffmpeg exited")) {
                Path logHintDir = session.outputDirectory();
                return Constants.MOD_NAME + " capture failed: FFmpeg exited early. Check " + logHintDir + " for a *.ffmpeg.log file. Lower ffmpeg.videoBitrateKbps or capture resolution if the encoder cannot keep up.";
            }
        }

        return Constants.MOD_NAME + " capture failed: " + message;
    }
}
