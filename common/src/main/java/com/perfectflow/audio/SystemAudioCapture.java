package com.perfectflow.audio;

import com.perfectflow.capture.CaptureSession;

public interface SystemAudioCapture {
    boolean isSupported();

    String start(CaptureSession session);

    void stop();

    void advanceFrame(CaptureSession session);

    long currentCapturedFrames();
}
