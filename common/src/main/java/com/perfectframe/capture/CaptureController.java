package com.perfectframe.capture;

import com.perfectframe.Constants;
import com.perfectframe.capture.export.FfmpegPipeExporter;
import com.perfectframe.capture.export.FrameExporter;
import com.perfectframe.capture.export.TgaSequenceExporter;
import com.perfectframe.capture.frame.CapturedFrame;
import com.perfectframe.capture.frame.PixelFormat;
import com.perfectframe.capture.pipeline.RenderCapturePipeline;
import com.perfectframe.config.PerfectFrameConfig;
import com.perfectframe.platform.Services;
import com.perfectframe.shader.ShaderPipelineAdapter;
import com.perfectframe.shader.ShaderPipelineAdapters;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public enum CaptureController {
    INSTANCE;

    private final RenderCapturePipeline capturePipeline = new RenderCapturePipeline();
    private final Map<String, FrameExporter> exporters = new LinkedHashMap<>();
    private PerfectFrameConfig config = new PerfectFrameConfig();
    private CaptureState state = CaptureState.IDLE;
    private CaptureSession session;
    private ShaderPipelineAdapter shaderAdapter;
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
            Minecraft minecraft = Minecraft.getInstance();
            Path outputRoot = minecraft.gameDirectory.toPath().resolve(config.capture.outputPath);
            Files.createDirectories(outputRoot);
            session = new CaptureSession(config, outputRoot);
            shaderAdapter = ShaderPipelineAdapters.select(minecraft, config.shader.captureMode);
            if (config.capture.recordDepth && !shaderAdapter.supportsDepthCapture(minecraft)) {
                notifyClient(Component.literal("Perfect Frame: depth capture disabled. " + shaderAdapter.unavailableDepthReason()));
            }
            exporters.clear();
            state = CaptureState.RECORDING;
            notifyClient(Component.literal("Perfect Frame recording started (" + shaderAdapter.id() + ")."));
        } catch (Exception exception) {
            Constants.LOG.error("Failed to start capture", exception);
            notifyClient(Component.literal("Perfect Frame failed to start: " + exception.getMessage()));
            closeExporters();
            session = null;
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
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null || minecraft.player == null) {
                return;
            }
            List<CapturedFrame> frames = capturePipeline.capture(minecraft, session, shaderAdapter);
            for (CapturedFrame frame : frames) {
                exporterFor(frame).export(frame);
            }
            if (!frames.isEmpty()) {
                session.advanceFrame();
            }
        } catch (Exception exception) {
            Constants.LOG.error("Capture failed", exception);
            notifyClient(Component.literal(buildFailureMessage(exception)));
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
        state = CaptureState.IDLE;
        notifyClient(Component.literal("Perfect Frame recording stopped (" + frames + " frames)."));
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

    private void notifyClient(Component component) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) {
            minecraft.gui.getChat().addMessage(component);
        }
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
                return "Perfect Frame capture failed: FFmpeg exited early. Check " + logHintDir + " for a *.ffmpeg.log file. Lower ffmpeg.videoBitrateKbps or capture resolution if the encoder cannot keep up.";
            }
        }

        return "Perfect Frame capture failed: " + message;
    }
}
