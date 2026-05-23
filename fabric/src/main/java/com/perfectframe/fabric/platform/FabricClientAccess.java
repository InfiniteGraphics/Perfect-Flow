package com.perfectframe.fabric.platform;

import com.perfectframe.capture.CaptureController;
import com.perfectframe.capture.CaptureSession;
import com.perfectframe.capture.frame.CapturedFrame;
import com.perfectframe.capture.pipeline.RenderCapturePipeline;
import com.perfectframe.platform.services.ClientAccess;
import com.perfectframe.shader.CaptureSource;
import net.minecraft.client.MinecraftClient;
import java.nio.file.Path;
import java.util.List;

public final class FabricClientAccess implements ClientAccess {
    private final RenderCapturePipeline capturePipeline = new RenderCapturePipeline();

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
