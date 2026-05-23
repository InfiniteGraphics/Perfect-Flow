package com.perfectframe.fabric.platform;

import com.perfectframe.capture.CaptureController;
import com.perfectframe.capture.CaptureSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

public final class FabricRecordingHud {
    private FabricRecordingHud() {
    }

    public static void render(DrawContext graphics, CaptureController controller) {
        CaptureSession session = controller.session();
        if (!controller.isRecording() || session == null || !session.config().capture.showRecordingHud) {
            return;
        }

        MinecraftClient minecraft = MinecraftClient.getInstance();
        String size = session.outputWidth() > 0 && session.outputHeight() > 0
                ? " " + session.outputWidth() + "x" + session.outputHeight()
                : "";
        String queue = session.exporterQueueCapacity() > 0
                ? " q" + session.exporterQueueDepth() + "/" + session.exporterQueueCapacity()
                : "";
        String sync = session.effectiveSyncMode().displayName();
        if (session.syncDowngraded()) {
            sync = sync + "*";
        }
        String audio = session.requestedAudioEnabled() ? (session.audioDowngraded() ? " A*" : " A") : "";
        String text = "REC " + session.capturedFrames() + "f " + session.scheduler().targetFps() + "fps " + sync + audio + size + queue;
        int width = minecraft.textRenderer.getWidth(text);
        int x = minecraft.getWindow().getScaledWidth() - width - 10;
        int y = 10;
        graphics.fill(x - 5, y - 4, x + width + 5, y + 12, 0x99000000);
        graphics.fill(x - 12, y + 1, x - 6, y + 7, 0xffff3333);
        graphics.drawText(minecraft.textRenderer, text, x, y, 0xffffffff, true);
    }
}
