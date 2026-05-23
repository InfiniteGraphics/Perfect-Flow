package com.perfectframe.capture;

import com.perfectframe.Constants;
import com.perfectframe.audio.SystemAudioCapture;
import com.perfectframe.capture.export.FfmpegPipeExporter;
import com.perfectframe.capture.export.FrameExporter;
import com.perfectframe.capture.export.TgaSequenceExporter;
import com.perfectframe.capture.frame.CapturedFrame;
import com.perfectframe.config.PerfectFlowConfig;
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
    private PerfectFlowConfig config = new PerfectFlowConfig();
    private CaptureState state = CaptureState.IDLE;
    private CaptureSession session;
    private ShaderPipelineAdapter shaderAdapter;
    private CaptureSource captureSource;
    private int captureSourceWidth = -1;
    private int captureSourceHeight = -1;
    private boolean keyToggleQueued;
    private boolean syncDowngradeNotified;
    private boolean audioDowngradeNotified;

    public void configure(PerfectFlowConfig config) {
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
            captureSource = resolveCaptureSource(true);
            if (config.shader.captureMode == PerfectFlowConfig.ShaderCaptureMode.OCULUS
                    && Services.PLATFORM.normalizeShaderCaptureMode(config.shader.captureMode) == PerfectFlowConfig.ShaderCaptureMode.IRIS) {
                notifyClient(Constants.MOD_NAME + ": OCULUS mode maps to IRIS on Fabric 1.20.4.");
            }
            exporters.clear();
            syncDowngradeNotified = false;
            audioDowngradeNotified = false;
            session.setEffectiveSyncMode(config.sync.mode);
            if (isAudioPotentiallySupported()) {
                SystemAudioCapture audioCapture = Services.PLATFORM.clientAccess().systemAudioCapture();
                String audioFailure = audioCapture.start(session);
                if (audioFailure == null) {
                    session.setAudioStatus(true, false, "");
                } else {
                    session.setAudioStatus(false, true, audioFailure);
                }
            } else if (config.audio != null && config.audio.enabled) {
                session.setAudioStatus(false, true, buildAudioDowngradeReason());
            } else {
                session.setAudioStatus(false, false, "");
            }
            session.scheduler().begin();
            state = CaptureState.RECORDING;
            Constants.LOG.info("{} capture source: {}", Constants.MOD_NAME, captureSource.label());
            notifyClient(Constants.MOD_NAME + " recording started (" + captureSource.label() + ").");
        } catch (Exception exception) {
            Constants.LOG.error("Failed to start capture", exception);
            notifyClient(Constants.MOD_NAME + " failed to start: " + exception.getMessage());
            closeExporters();
            session = null;
            clearCaptureSource();
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
            PerfectFlowConfig.SyncMode effectiveSyncMode = resolveSyncMode();
            session.setEffectiveSyncMode(effectiveSyncMode);
            if (effectiveSyncMode == PerfectFlowConfig.SyncMode.CLIENT_ONLY
                    && config.sync.mode == PerfectFlowConfig.SyncMode.NORMAL
                    && !syncDowngradeNotified
                    && !Services.PLATFORM.clientAccess().isSingleplayerWorld()) {
                syncDowngradeNotified = true;
                notifyClient(Constants.MOD_NAME + ": sync mode downgraded to Client Only in multiplayer.");
            }
            if (session.requestedAudioEnabled() && session.audioDowngraded() && !audioDowngradeNotified) {
                audioDowngradeNotified = true;
                String detail = session.audioStatusDetail();
                notifyClient(detail == null || detail.isBlank()
                        ? Constants.MOD_NAME + ": audio recording downgraded to video-only."
                        : Constants.MOD_NAME + ": audio recording downgraded to video-only. " + detail);
            }
            session.scheduler().awaitFrameWindow(effectiveSyncMode == PerfectFlowConfig.SyncMode.NORMAL);
            CaptureSource activeSource = resolveCaptureSource(false);
            List<CapturedFrame> frames = Services.PLATFORM.clientAccess().captureFrames(session, activeSource);
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
                if (session.effectiveAudioEnabled()) {
                    Services.PLATFORM.clientAccess().systemAudioCapture().advanceFrame(session);
                }
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
        Services.PLATFORM.clientAccess().systemAudioCapture().stop();
        closeExporters();
        session = null;
        clearCaptureSource();
        syncDowngradeNotified = false;
        audioDowngradeNotified = false;
        state = CaptureState.IDLE;
        notifyClient(Constants.MOD_NAME + " recording stopped (" + frames + " frames).");
    }

    private PerfectFlowConfig.SyncMode resolveSyncMode() {
        if (config.sync.mode != PerfectFlowConfig.SyncMode.NORMAL) {
            return PerfectFlowConfig.SyncMode.CLIENT_ONLY;
        }
        if (!Services.PLATFORM.clientAccess().isWorldReady()) {
            return PerfectFlowConfig.SyncMode.NORMAL;
        }
        return Services.PLATFORM.clientAccess().isSingleplayerWorld()
                ? PerfectFlowConfig.SyncMode.NORMAL
                : PerfectFlowConfig.SyncMode.CLIENT_ONLY;
    }

    private CaptureSource resolveCaptureSource(boolean forceRefresh) {
        if (shaderAdapter == null) {
            throw new IllegalStateException("No capture pipeline is available.");
        }
        if (forceRefresh || shouldRefreshCaptureSource()) {
            CaptureSource resolved = shaderAdapter.resolve();
            validateCaptureSource(resolved);
            if (captureSource != null) {
                captureSource.destroy();
            }
            captureSource = resolved;
            captureSourceWidth = resolved.width();
            captureSourceHeight = resolved.height();
        }
        return captureSource;
    }

    private boolean shouldRefreshCaptureSource() {
        if (captureSource == null) {
            return true;
        }
        if (!hasRequiredStreams(captureSource)) {
            return true;
        }
        int width = captureSource.width();
        int height = captureSource.height();
        return width <= 0 || height <= 0 || width != captureSourceWidth || height != captureSourceHeight;
    }

    private boolean hasRequiredStreams(CaptureSource source) {
        boolean needsColor = config.capture.recordColor || config.capture.recordAlpha;
        if (needsColor && !source.hasColor()) {
            return false;
        }
        return !config.capture.recordDepth || source.hasDepth();
    }

    private void clearCaptureSource() {
        if (captureSource != null) {
            captureSource.destroy();
        }
        captureSource = null;
        captureSourceWidth = -1;
        captureSourceHeight = -1;
    }

    private void validateCaptureSource(CaptureSource source) {
        boolean needsColor = config.capture.recordColor || config.capture.recordAlpha;
        if (source == null) {
            throw new IllegalStateException("Capture source is unavailable.");
        }
        if (needsColor && !source.hasColor()) {
            throw new IllegalStateException(source.colorUnavailableReason());
        }
        if (config.capture.recordDepth && !source.hasDepth()) {
            throw new IllegalStateException(source.depthUnavailableReason());
        }
        if (!needsColor && !config.capture.recordDepth) {
            throw new IllegalStateException("No capture streams are enabled.");
        }
        if (source.width() <= 0 || source.height() <= 0) {
            throw new IllegalStateException("Capture source has invalid dimensions.");
        }
    }

    private FrameExporter exporterFor(CapturedFrame frame) throws Exception {
        FrameExporter existing = exporters.get(frame.streamName());
        if (existing != null) {
            return existing;
        }
        FrameExporter created = config.capture.outputMode == PerfectFlowConfig.OutputMode.TGA_SEQUENCE
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

        if (config.capture.outputMode == PerfectFlowConfig.OutputMode.FFMPEG_MP4 && session != null) {
            String lower = message.toLowerCase();
            if (lower.contains("pipe") || lower.contains("ffmpeg exited")) {
                Path logHintDir = session.outputDirectory();
                return Constants.MOD_NAME + " capture failed: FFmpeg exited early. Check " + logHintDir + " for a *.ffmpeg.log file. Lower ffmpeg.videoBitrateKbps or capture resolution if the encoder cannot keep up.";
            }
        }

        return Constants.MOD_NAME + " capture failed: " + message;
    }

    private boolean isAudioPotentiallySupported() {
        return config.audio != null
                && config.audio.enabled
                && Services.PLATFORM.clientAccess().systemAudioCapture().isSupported()
                && config.capture.outputMode == PerfectFlowConfig.OutputMode.FFMPEG_MP4
                && (config.ffmpeg.videoArgs == null || config.ffmpeg.videoArgs.isBlank())
                && (config.capture.recordColor || config.capture.recordAlpha);
    }

    private String buildAudioDowngradeReason() {
        if (config.audio == null || !config.audio.enabled) {
            return "";
        }
        if (config.capture.outputMode != PerfectFlowConfig.OutputMode.FFMPEG_MP4) {
            return "Audio recording only works with FFmpeg MP4 output.";
        }
        if (config.ffmpeg.videoArgs != null && !config.ffmpeg.videoArgs.isBlank()) {
            return "Audio recording is disabled when advanced FFmpeg video args are in use.";
        }
        return "Process audio capture is unavailable on this platform or configuration.";
    }
}
