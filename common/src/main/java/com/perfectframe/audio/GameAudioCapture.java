package com.perfectframe.audio;

import com.perfectframe.capture.CaptureSession;

public interface GameAudioCapture {
    boolean isSupported();

    String start(CaptureSession session);

    void stop();

    void advanceFrame(CaptureSession session);

    void onSoundPlayed(Object engine, Object instance);

    void onSoundStopped(Object instance);

    void onListenerUpdated(Object transform);

    void onCategoryVolumeUpdated(Object engine, Object source, float gain);

    void onPauseAll();

    void onResume();

    void onSoundEngineDestroyed();
}
