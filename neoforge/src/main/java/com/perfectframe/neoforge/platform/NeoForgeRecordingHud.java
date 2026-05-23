package com.perfectframe.neoforge.platform;

import com.perfectframe.capture.CaptureController;
import com.perfectframe.capture.CaptureSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class NeoForgeRecordingHud {
    private NeoForgeRecordingHud() {
    }

    public static void render(GuiGraphics graphics, CaptureController controller) {
        CaptureSession session = controller.session();
        if (!controller.isRecording() || session == null || !session.config().capture.showRecordingHud) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
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
        String text = "REC " + session.capturedFrames() + "f " + session.scheduler().targetFps() + "fps " + sync + size + queue;
        int width = minecraft.font.width(text);
        int x = minecraft.getWindow().getGuiScaledWidth() - width - 10;
        int y = 10;
        graphics.fill(x - 5, y - 4, x + width + 5, y + 12, 0x99000000);
        graphics.fill(x - 12, y + 1, x - 6, y + 7, 0xffff3333);
        graphics.drawString(minecraft.font, text, x, y, 0xffffffff, true);
    }
}
