package com.perfectframe.audio;

import com.perfectframe.capture.CaptureSession;

public interface SystemAudioCapture {
    boolean isSupported();

    String start(CaptureSession session);

    void stop();

    void advanceFrame(CaptureSession session);
}
