package com.perfectframe.audio;

import com.perfectframe.capture.CaptureSession;

public final class NoopGameAudioCapture implements GameAudioCapture {
    @Override
    public boolean isSupported() {
        return false;
    }

    @Override
    public String start(CaptureSession session) {
        return "Game audio capture is not available on this loader yet.";
    }

    @Override
    public void stop() {
    }

    @Override
    public void advanceFrame(CaptureSession session) {
    }

    @Override
    public void onSoundPlayed(Object engine, Object instance) {
    }

    @Override
    public void onSoundStopped(Object instance) {
    }

    @Override
    public void onListenerUpdated(Object transform) {
    }

    @Override
    public void onCategoryVolumeUpdated(Object engine, Object source, float gain) {
    }

    @Override
    public void onPauseAll() {
    }

    @Override
    public void onResume() {
    }

    @Override
    public void onSoundEngineDestroyed() {
    }
}
