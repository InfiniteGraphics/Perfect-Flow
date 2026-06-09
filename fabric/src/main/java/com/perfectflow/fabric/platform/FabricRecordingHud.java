package com.perfectflow.fabric.platform;

import com.perfectflow.capture.CaptureController;
import com.perfectflow.capture.CaptureSession;
import net.minecraft.client.Minecraft;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class FabricRecordingHud {
    private FabricRecordingHud() {
    }

    public static void render(Object graphics, CaptureController controller) {
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
        String audio = session.requestedAudioEnabled() ? (session.audioDowngraded() ? " A*" : " A") : "";
        String text = "REC " + session.capturedFrames() + "f " + session.scheduler().targetFps() + "fps " + sync + audio + size + queue;
        int width = minecraft.font.width(text);
        int x = minecraft.getWindow().getGuiScaledWidth() - width - 10;
        int y = 10;
        renderWithGuiGraphics(graphics, minecraft, text, x, y, width);
    }

    private static void renderWithGuiGraphics(Object graphics, Minecraft minecraft, String text, int x, int y, int width) {
        if (graphics == null || !graphics.getClass().getName().equals("net.minecraft.client.gui.GuiGraphics")) {
            return;
        }

        try {
            Method fill = graphics.getClass().getMethod("fill", int.class, int.class, int.class, int.class, int.class);
            Method drawString = graphics.getClass().getMethod(
                    "drawString",
                    minecraft.font.getClass(),
                    String.class,
                    int.class,
                    int.class,
                    int.class,
                    boolean.class
            );
            fill.invoke(graphics, x - 5, y - 4, x + width + 5, y + 12, 0x99000000);
            fill.invoke(graphics, x - 12, y + 1, x - 6, y + 7, 0xffff3333);
            drawString.invoke(graphics, minecraft.font, text, x, y, 0xffffffff, true);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
        }
    }
}
