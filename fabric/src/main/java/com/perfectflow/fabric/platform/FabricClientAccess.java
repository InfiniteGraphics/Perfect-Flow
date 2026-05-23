package com.perfectflow.fabric.platform;

import com.perfectflow.capture.CaptureController;
import com.perfectflow.capture.CaptureSession;
import com.perfectflow.audio.SystemAudioCapture;
import com.perfectflow.audio.WindowsProcessAudioCapture;
import com.perfectflow.capture.frame.CapturedFrame;
import com.perfectflow.capture.pipeline.RenderCapturePipeline;
import com.perfectflow.platform.services.ClientAccess;
import com.perfectflow.shader.CaptureSource;
import net.minecraft.client.MinecraftClient;
import java.nio.file.Path;
import java.util.List;

public final class FabricClientAccess implements ClientAccess {
    private final RenderCapturePipeline capturePipeline = new RenderCapturePipeline();
    private final SystemAudioCapture systemAudioCapture = WindowsProcessAudioCapture.createOrNoop();

    @Override
    public Path gameDirectory() {
        return MinecraftClient.getInstance().runDirectory.toPath();
    }

    @Override
    public boolean isWorldReady() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.world != null && client.player != null;
    }

    @Override
    public boolean isSingleplayerWorld() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.getServer() != null;
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal(message));
        }
    }

    @Override
    public void renderRecordingHud(Object graphicsContext) {
        if (graphicsContext instanceof net.minecraft.client.gui.DrawContext drawContext) {
            FabricRecordingHud.render(drawContext, CaptureController.INSTANCE);
        }
    }
}
