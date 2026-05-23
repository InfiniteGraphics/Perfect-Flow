package com.perfectframe.audio;

import com.perfectframe.capture.CaptureSession;

public final class NoopSystemAudioCapture implements SystemAudioCapture {
    @Override
    public boolean isSupported() {
        return false;
    }

    @Override
    public String start(CaptureSession session) {
        return "Process audio capture is not available on this platform yet.";
    }

    @Override
    public void stop() {
    }

    @Override
    public void advanceFrame(CaptureSession session) {
    }
}
