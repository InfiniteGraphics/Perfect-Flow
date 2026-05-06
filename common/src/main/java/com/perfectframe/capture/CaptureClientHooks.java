package com.perfectframe.capture;

import com.perfectframe.ui.RecordingHud;
import net.minecraft.client.gui.GuiGraphics;

public final class CaptureClientHooks {
    private CaptureClientHooks() {
    }

    public static void afterClientTick() {
        CaptureController.INSTANCE.afterClientTick();
    }

    public static void requestToggle() {
        CaptureController.INSTANCE.toggle();
    }

    public static void afterWorldRender() {
        CaptureController.INSTANCE.captureRenderedFrame();
    }

    public static void renderHud(GuiGraphics graphics) {
        RecordingHud.render(graphics, CaptureController.INSTANCE);
    }
}
