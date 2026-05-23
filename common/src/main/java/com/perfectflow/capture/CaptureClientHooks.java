package com.perfectflow.capture;

import com.perfectflow.platform.Services;

public final class CaptureClientHooks {
    private CaptureClientHooks() {
    }

    public static void afterClientTick() {
        CaptureController.INSTANCE.afterClientTick();
    }

    public static void requestToggle() {
        CaptureController.INSTANCE.toggle();
    }

    public static void captureFinalFrame() {
        CaptureController.INSTANCE.captureRenderedFrame();
    }

    public static void renderHud(Object graphicsContext) {
        Services.PLATFORM.clientAccess().renderRecordingHud(graphicsContext);
    }
}
