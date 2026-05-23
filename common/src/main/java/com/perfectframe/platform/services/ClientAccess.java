package com.perfectframe.platform.services;

import com.perfectframe.capture.frame.CapturedFrame;
import com.perfectframe.audio.SystemAudioCapture;
import com.perfectframe.shader.CaptureSource;

import java.nio.file.Path;
import java.util.List;

public interface ClientAccess {
    Path gameDirectory();

    boolean isWorldReady();

    boolean isSingleplayerWorld();

    List<CapturedFrame> captureFrames(com.perfectframe.capture.CaptureSession session, CaptureSource source);

    SystemAudioCapture systemAudioCapture();

    void postChatMessage(String message);

    void renderRecordingHud(Object graphicsContext);
}
