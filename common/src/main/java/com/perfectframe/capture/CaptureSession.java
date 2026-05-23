package com.perfectframe.capture;

import com.perfectframe.config.PerfectFlowConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class CaptureSession {
    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final PerfectFlowConfig config;
    private final Path outputDirectory;
    private final String name;
    private final FrameScheduler scheduler;
    private final PerfectFlowConfig.SyncMode requestedSyncMode;
    private final boolean requestedAudioEnabled;
    private PerfectFlowConfig.SyncMode effectiveSyncMode;
    private boolean effectiveAudioEnabled;
    private boolean audioDowngraded;
    private String audioStatusDetail = "";
    private boolean preciseAudioSyncAvailable = true;
    private String preciseAudioSyncDetail = "";
    private int captureWidth;
    private int captureHeight;
    private int outputWidth;
    private int outputHeight;
    private int exporterQueueDepth;
    private int exporterQueueCapacity;
    private long capturedFrames;
    private BufferedWriter audioAnchorWriter;
    private long audioAnchorCount;

    public CaptureSession(PerfectFlowConfig config, Path outputDirectory) {
        this.config = config;
        this.outputDirectory = outputDirectory;
        this.name = LocalDateTime.now().format(NAME_FORMAT);
        this.scheduler = new FrameScheduler(config.capture.fps, config.sync.engineSpeed);
        this.requestedSyncMode = config.sync.mode;
        this.requestedAudioEnabled = config.audio != null && config.audio.enabled;
        this.effectiveSyncMode = config.sync.mode;
        this.effectiveAudioEnabled = requestedAudioEnabled;
    }

    public PerfectFlowConfig config() {
        return config;
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    public String name() {
        return name;
    }

    public Path audioTempFile() {
        return outputDirectory.resolve(name + "_color.audio.tmp.raw");
    }

    public Path audioMetadataFile() {
        return outputDirectory.resolve(name + "_color.audio.tmp.properties");
    }

    public Path audioCaptureLogFile() {
        return outputDirectory.resolve(name + "_color.audio-helper.log");
    }

    public Path audioAnchorFile() {
        return outputDirectory.resolve(name + "_color.audio-anchors.csv");
    }

    public FrameScheduler scheduler() {
        return scheduler;
    }

    public PerfectFlowConfig.SyncMode requestedSyncMode() {
        return requestedSyncMode;
    }

    public PerfectFlowConfig.SyncMode effectiveSyncMode() {
        return effectiveSyncMode;
    }

    public boolean syncDowngraded() {
        return requestedSyncMode == PerfectFlowConfig.SyncMode.NORMAL
                && effectiveSyncMode == PerfectFlowConfig.SyncMode.CLIENT_ONLY;
    }

    public void setEffectiveSyncMode(PerfectFlowConfig.SyncMode effectiveSyncMode) {
        this.effectiveSyncMode = effectiveSyncMode == null ? PerfectFlowConfig.SyncMode.CLIENT_ONLY : effectiveSyncMode;
    }

    public boolean requestedAudioEnabled() {
        return requestedAudioEnabled;
    }

    public boolean effectiveAudioEnabled() {
        return effectiveAudioEnabled;
    }

    public boolean audioDowngraded() {
        return audioDowngraded;
    }

    public String audioStatusDetail() {
        return audioStatusDetail;
    }

    public boolean preciseAudioSyncAvailable() {
        return preciseAudioSyncAvailable;
    }

    public boolean preciseAudioSyncDowngraded() {
        return !preciseAudioSyncAvailable;
    }

    public String preciseAudioSyncDetail() {
        return preciseAudioSyncDetail;
    }

    public long audioAnchorCount() {
        return audioAnchorCount;
    }

    public void setAudioStatus(boolean effectiveAudioEnabled, boolean downgraded, String detail) {
        this.effectiveAudioEnabled = effectiveAudioEnabled;
        this.audioDowngraded = downgraded;
        this.audioStatusDetail = detail == null ? "" : detail;
    }

    public long capturedFrames() {
        return capturedFrames;
    }

    public int outputWidth() {
        return outputWidth > 0 ? outputWidth : captureWidth;
    }

    public int outputHeight() {
        return outputHeight > 0 ? outputHeight : captureHeight;
    }

    public void setCaptureSize(int captureWidth, int captureHeight) {
        this.captureWidth = captureWidth;
        this.captureHeight = captureHeight;
    }

    public void setOutputSize(int outputWidth, int outputHeight) {
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
    }

    public int exporterQueueDepth() {
        return exporterQueueDepth;
    }

    public int exporterQueueCapacity() {
        return exporterQueueCapacity;
    }

    public void setExporterQueueStatus(int exporterQueueDepth, int exporterQueueCapacity) {
        this.exporterQueueDepth = Math.max(0, exporterQueueDepth);
        this.exporterQueueCapacity = Math.max(0, exporterQueueCapacity);
    }

    public void advanceFrame() {
        capturedFrames++;
        scheduler.advance();
    }

    public boolean reachedFrameLimit() {
        return config.capture.frameLimit >= 0 && capturedFrames >= config.capture.frameLimit;
    }

    public void prepareAudioAnchors() throws IOException {
        closeAudioAnchors();
        Path anchorFile = audioAnchorFile();
        Files.deleteIfExists(anchorFile);
        audioAnchorWriter = Files.newBufferedWriter(anchorFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        audioAnchorWriter.write("videoFramesCompleted,audioFramesCaptured");
        audioAnchorWriter.newLine();
        audioAnchorWriter.flush();
        preciseAudioSyncAvailable = true;
        preciseAudioSyncDetail = "";
        audioAnchorCount = 0L;
    }

    public void recordAudioAnchor(long videoFramesCompleted, long audioFramesCaptured) {
        if (audioAnchorWriter == null || !preciseAudioSyncAvailable) {
            return;
        }
        try {
            audioAnchorWriter.write(videoFramesCompleted + "," + audioFramesCaptured);
            audioAnchorWriter.newLine();
            audioAnchorCount++;
            if (audioAnchorCount <= 4 || (audioAnchorCount % 12L) == 0L) {
                audioAnchorWriter.flush();
            }
        } catch (IOException exception) {
            markPreciseAudioSyncUnavailable("Failed to write audio sync anchors: " + exception.getMessage());
        }
    }

    public void finishAudioAnchors() {
        if (audioAnchorWriter == null) {
            return;
        }
        try {
            audioAnchorWriter.flush();
        } catch (IOException exception) {
            if (preciseAudioSyncAvailable) {
                markPreciseAudioSyncUnavailable("Failed to flush audio sync anchors: " + exception.getMessage());
            }
        } finally {
            closeAudioAnchors();
        }
    }

    public void markPreciseAudioSyncUnavailable(String detail) {
        preciseAudioSyncAvailable = false;
        preciseAudioSyncDetail = detail == null ? "" : detail;
        closeAudioAnchors();
    }

    private void closeAudioAnchors() {
        if (audioAnchorWriter == null) {
            return;
        }
        try {
            audioAnchorWriter.close();
        } catch (IOException ignored) {
        }
        audioAnchorWriter = null;
    }
}
