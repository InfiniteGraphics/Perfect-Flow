package com.perfectframe.capture.pipeline;

import com.perfectframe.capture.CaptureSession;
import com.perfectframe.capture.frame.CapturedFrame;
import com.perfectframe.capture.frame.PixelFormat;
import com.perfectframe.config.PerfectFrameConfig;
import com.perfectframe.shader.CaptureAttachment;
import com.perfectframe.shader.CaptureSource;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

public final class RenderCapturePipeline {
    public List<CapturedFrame> capture(CaptureSession session, CaptureSource source) {
        PerfectFrameConfig config = session.config();
        int width = source.width();
        int height = source.height();
        session.setCaptureSize(width, height);
        List<CapturedFrame> frames = new ArrayList<>();

        if (config.capture.recordColor) {
            PixelFormat format = config.capture.recordAlpha ? PixelFormat.BGRA32 : PixelFormat.BGR24;
            frames.add(new CapturedFrame("color", session.capturedFrames(), width, height, format, readColor(source.colorAttachment(), width, height, format)));
        }

        if (config.capture.recordAlpha) {
            frames.add(new CapturedFrame("alpha", session.capturedFrames(), width, height, PixelFormat.BGRA32, readColor(source.colorAttachment(), width, height, PixelFormat.BGRA32)));
        }

        if (config.capture.recordDepth) {
            frames.add(new CapturedFrame("depth", session.capturedFrames(), width, height, PixelFormat.DEPTH_BGR24, readDepth(source.depthAttachment(), width, height)));
        }

        return frames;
    }

    private ByteBuffer readColor(CaptureAttachment attachment, int width, int height, PixelFormat format) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * format.bytesPerPixel());
        attachment.bindRead();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        int glFormat = format == PixelFormat.BGRA32 ? GL12.GL_BGRA : GL12.GL_BGR;
        GL11.glReadPixels(0, 0, width, height, glFormat, GL11.GL_UNSIGNED_BYTE, pixels);
        attachment.unbindRead();
        pixels.rewind();
        return pixels;
    }

    private ByteBuffer readDepth(CaptureAttachment attachment, int width, int height) {
        FloatBuffer depth = BufferUtils.createFloatBuffer(width * height);
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * PixelFormat.DEPTH_BGR24.bytesPerPixel());
        attachment.bindRead();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
        attachment.unbindRead();
        depth.rewind();
        while (depth.hasRemaining()) {
            float normalized = linearizeDepth(depth.get());
            byte value = (byte) Math.round(normalized * 255.0F);
            pixels.put(value);
            pixels.put(value);
            pixels.put(value);
        }
        pixels.rewind();
        return pixels;
    }

    private float linearizeDepth(float z) {
        float near = 0.05F;
        float far = 1024.0F;
        float ndc = z * 2.0F - 1.0F;
        float linear = (2.0F * near * far) / (far + near - ndc * (far - near));
        return Math.max(0.0F, Math.min(1.0F, linear / far));
    }
}
