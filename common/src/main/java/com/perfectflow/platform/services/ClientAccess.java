package com.perfectflow.platform.services;

import com.perfectflow.capture.CaptureSession;
import com.perfectflow.capture.frame.CapturedFrame;
import com.perfectflow.audio.SystemAudioCapture;
import com.perfectflow.shader.CaptureSource;

import java.nio.file.Path;
import java.util.List;

public interface ClientAccess {
    Path gameDirectory();

    boolean isWorldReady();

    boolean isSingleplayerWorld();

    List<CapturedFrame> captureFrames(CaptureSession session, CaptureSource source);

    SystemAudioCapture systemAudioCapture();

    void postChatMessage(String message);

    void renderRecordingHud(Object graphicsContext);
}
