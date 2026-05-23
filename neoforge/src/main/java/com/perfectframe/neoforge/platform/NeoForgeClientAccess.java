package com.perfectframe.neoforge.platform;

import com.perfectframe.capture.CaptureController;
import com.perfectframe.capture.CaptureSession;
import com.perfectframe.capture.frame.CapturedFrame;
import com.perfectframe.capture.pipeline.RenderCapturePipeline;
import com.perfectframe.platform.services.ClientAccess;
import com.perfectframe.shader.CaptureSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;

public final class NeoForgeClientAccess implements ClientAccess {
    private final RenderCapturePipeline capturePipeline = new RenderCapturePipeline();

    @Override
    public Path gameDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    @Override
    public boolean isWorldReady() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && minecraft.player != null;
    }

    @Override
    public boolean isSingleplayerWorld() {
        return Minecraft.getInstance().getSingleplayerServer() != null;
    }

    @Override
    public List<CapturedFrame> captureFrames(CaptureSession session, CaptureSource source) {
        return capturePipeline.capture(session, source);
    }

    @Override
    public void postChatMessage(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) {
            minecraft.gui.getChat().addMessage(Component.literal(message));
        }
    }

    @Override
    public void renderRecordingHud(Object graphicsContext) {
        if (graphicsContext instanceof GuiGraphics graphics) {
            NeoForgeRecordingHud.render(graphics, CaptureController.INSTANCE);
        }
    }
}
