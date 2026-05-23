package com.perfectflow.neoforge.platform;

import com.perfectflow.capture.CaptureController;
import com.perfectflow.capture.CaptureSession;
import com.perfectflow.audio.SystemAudioCapture;
import com.perfectflow.audio.WindowsProcessAudioCapture;
import com.perfectflow.capture.frame.CapturedFrame;
import com.perfectflow.capture.pipeline.RenderCapturePipeline;
import com.perfectflow.platform.services.ClientAccess;
import com.perfectflow.shader.CaptureSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;

public final class NeoForgeClientAccess implements ClientAccess {
    private final RenderCapturePipeline capturePipeline = new RenderCapturePipeline();
    private final SystemAudioCapture systemAudioCapture = WindowsProcessAudioCapture.createOrNoop();

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
    public SystemAudioCapture systemAudioCapture() {
        return systemAudioCapture;
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
