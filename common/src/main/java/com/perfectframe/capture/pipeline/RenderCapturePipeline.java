package com.perfectframe.capture.pipeline;

import com.perfectframe.capture.CaptureSession;
import com.perfectframe.capture.frame.CapturedFrame;
import com.perfectframe.capture.frame.PixelFormat;
import com.perfectframe.config.PerfectFlowConfig;
import com.perfectframe.shader.CaptureAttachment;
import com.perfectframe.shader.CaptureSource;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class RenderCapturePipeline {
    private ByteBuffer reusableBgraBuffer;
    private FloatBuffer reusableDepthFloatBuffer;
    private final DirectByteBufferPool framePool = new DirectByteBufferPool();
    private ByteBuffer[] frameBlendBuffers = new ByteBuffer[0];
    private int frameBlendBufferWidth = -1;
    private int frameBlendBufferHeight = -1;
    private int frameBlendStart;
    private int frameBlendCount;

    public List<CapturedFrame> capture(CaptureSession session, CaptureSource source) {
        PerfectFlowConfig config = session.config();
        int width = source.width();
        int height = source.height();
        session.setCaptureSize(width, height);
        List<CapturedFrame> frames = new ArrayList<>();

        boolean needsColor = config.capture.recordColor || config.capture.recordAlpha;
        if (needsColor) {
            ByteBuffer bgra = null;
            try {
                if (config.capture.recordAlpha || config.motionBlur.enabled) {
                    bgra = readColor(source.colorAttachment(), width, height, PixelFormat.BGRA32, reusableBgraBuffer);
                    reusableBgraBuffer = bgra;
                }

                if (config.capture.recordColor) {
                    ByteBuffer colorPixels = borrowFrameBuffer(width, height, PixelFormat.BGR24);
                    if (config.motionBlur.enabled) {
                        applyMotionBlur(config, width, height, bgra, colorPixels);
                    } else if (bgra == null) {
                        colorPixels = readColor(source.colorAttachment(), width, height, PixelFormat.BGR24, colorPixels);
                    } else {
                        extractColorBgr(bgra, width, height, colorPixels);
                    }
                    ByteBuffer colorFrame = colorPixels;
                    frames.add(new CapturedFrame("color", session.capturedFrames(), width, height, PixelFormat.BGR24, colorFrame,
                            () -> releaseFrameBuffer(width, height, PixelFormat.BGR24, colorFrame)));
                } else {
                    clearFrameBlendHistory();
                }

                if (config.capture.recordAlpha) {
                    ByteBuffer alphaMask = borrowFrameBuffer(width, height, PixelFormat.ALPHA_MASK_BGR24);
                    extractAlphaMask(bgra, width, height, alphaMask);
                    frames.add(new CapturedFrame("alpha", session.capturedFrames(), width, height, PixelFormat.ALPHA_MASK_BGR24, alphaMask,
                            () -> releaseFrameBuffer(width, height, PixelFormat.ALPHA_MASK_BGR24, alphaMask)));
                }
            } finally {
                if (!config.capture.recordColor) {
                    clearFrameBlendHistory();
                }
            }
        } else {
            clearFrameBlendHistory();
        }

        if (config.capture.recordDepth) {
            ByteBuffer depthPixels = borrowFrameBuffer(width, height, PixelFormat.DEPTH_BGR24);
            readDepth(source.depthAttachment(), width, height, depthPixels);
            frames.add(new CapturedFrame("depth", session.capturedFrames(), width, height, PixelFormat.DEPTH_BGR24, depthPixels,
                    () -> releaseFrameBuffer(width, height, PixelFormat.DEPTH_BGR24, depthPixels)));
        }

        return frames;
    }

    private ByteBuffer readColor(CaptureAttachment attachment, int width, int height, PixelFormat format, ByteBuffer reusableBuffer) {
        int expectedBytes = width * height * format.bytesPerPixel();
        ByteBuffer pixels = ensureByteBuffer(reusableBuffer, expectedBytes);
        pixels.clear();
        attachment.bindRead();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        int glFormat = format == PixelFormat.BGRA32 ? GL12.GL_BGRA : GL12.GL_BGR;
        GL11.glReadPixels(0, 0, width, height, glFormat, GL11.GL_UNSIGNED_BYTE, pixels);
        attachment.unbindRead();
        resetNativeReadBuffer(pixels, expectedBytes);
        return pixels;
    }

    private void readDepth(CaptureAttachment attachment, int width, int height, ByteBuffer output) {
        int expectedPixels = width * height;
        reusableDepthFloatBuffer = ensureFloatBuffer(reusableDepthFloatBuffer, expectedPixels);
        reusableDepthFloatBuffer.clear();
        output.clear();
        attachment.bindRead();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, reusableDepthFloatBuffer);
        attachment.unbindRead();
        resetNativeReadBuffer(reusableDepthFloatBuffer, expectedPixels);
        while (reusableDepthFloatBuffer.hasRemaining()) {
            float normalized = mapDepth(reusableDepthFloatBuffer.get());
            byte value = (byte) Math.round(normalized * 255.0F);
            output.put(value);
            output.put(value);
            output.put(value);
        }
        output.flip();
    }

    private void applyMotionBlur(PerfectFlowConfig config, int width, int height, ByteBuffer bgra, ByteBuffer output) {
        switch (config.motionBlur.mode) {
            case FRAME_BLEND -> applyFrameBlend(width, height, bgra, config.motionBlur, output);
            case ACCUMULATION -> applyAccumulation(width, height, bgra, config.motionBlur, output);
        }
    }

    private void applyFrameBlend(int width, int height, ByteBuffer bgra, PerfectFlowConfig.MotionBlur settings, ByteBuffer output) {
        ByteBuffer currentFrame = obtainFrameBlendSlot(width, height, settings.blendFrameCount);
        extractColorBgr(bgra, width, height, currentFrame);

        output.clear();
        int pixels = width * height;
        double strength = settings.shutterFraction;
        for (int i = 0; i < pixels; i++) {
            double totalWeight = 0.0D;
            double blue = 0.0D;
            double green = 0.0D;
            double red = 0.0D;
            for (int historyIndex = 0; historyIndex < frameBlendCount; historyIndex++) {
                ByteBuffer history = frameBlendBuffers[(frameBlendStart + historyIndex) % frameBlendBuffers.length];
                int base = i * 3;
                double t = frameBlendCount <= 1 ? 0.0D : historyIndex / (double) (frameBlendCount - 1);
                double weight = 1.0D - strength + (strength * (t + 1.0D / frameBlendCount));
                totalWeight += weight;
                blue += Byte.toUnsignedInt(history.get(base)) * weight;
                green += Byte.toUnsignedInt(history.get(base + 1)) * weight;
                red += Byte.toUnsignedInt(history.get(base + 2)) * weight;
            }
            output.put((byte) Math.round(blue / totalWeight));
            output.put((byte) Math.round(green / totalWeight));
            output.put((byte) Math.round(red / totalWeight));
        }
        output.flip();
    }

    private void applyAccumulation(int width, int height, ByteBuffer bgra, PerfectFlowConfig.MotionBlur settings, ByteBuffer output) {
        verifyReadableBytes("BGRA color buffer", bgra, width * height * PixelFormat.BGRA32.bytesPerPixel());
        output.clear();
        int pixels = width * height;
        int samples = Math.max(1, settings.sampleCount);
        double shutterWeight = Math.max(0.0D, Math.min(1.0D, settings.shutterFraction));
        double accumulationFactor = 0.82D + (0.18D * shutterWeight);
        for (int i = 0; i < pixels; i++) {
            int base = i * 4;
            int blue = (int) Math.round((Byte.toUnsignedInt(bgra.get(base)) * accumulationFactor * samples) / samples);
            int green = (int) Math.round((Byte.toUnsignedInt(bgra.get(base + 1)) * accumulationFactor * samples) / samples);
            int red = (int) Math.round((Byte.toUnsignedInt(bgra.get(base + 2)) * accumulationFactor * samples) / samples);
            output.put((byte) Math.max(0, Math.min(255, blue)));
            output.put((byte) Math.max(0, Math.min(255, green)));
            output.put((byte) Math.max(0, Math.min(255, red)));
        }
        output.flip();
    }

    private ByteBuffer obtainFrameBlendSlot(int width, int height, int blendFrameCount) {
        int capacity = Math.max(1, blendFrameCount);
        if (frameBlendBuffers.length != capacity || frameBlendBufferWidth != width || frameBlendBufferHeight != height) {
            frameBlendBuffers = new ByteBuffer[capacity];
            frameBlendBufferWidth = width;
            frameBlendBufferHeight = height;
            frameBlendStart = 0;
            frameBlendCount = 0;
        }

        int slotIndex;
        if (frameBlendCount < frameBlendBuffers.length) {
            slotIndex = (frameBlendStart + frameBlendCount) % frameBlendBuffers.length;
            frameBlendCount++;
        } else {
            slotIndex = frameBlendStart;
            frameBlendStart = (frameBlendStart + 1) % frameBlendBuffers.length;
        }

        ByteBuffer slot = ensureByteBuffer(frameBlendBuffers[slotIndex], width * height * PixelFormat.BGR24.bytesPerPixel());
        frameBlendBuffers[slotIndex] = slot;
        slot.clear();
        return slot;
    }

    private void clearFrameBlendHistory() {
        frameBlendStart = 0;
        frameBlendCount = 0;
    }

    private ByteBuffer borrowFrameBuffer(int width, int height, PixelFormat format) {
        return framePool.borrow(width, height, format);
    }

    private void releaseFrameBuffer(int width, int height, PixelFormat format, ByteBuffer buffer) {
        framePool.release(width, height, format, buffer);
    }

    private void extractColorBgr(ByteBuffer bgra, int width, int height, ByteBuffer target) {
        verifyReadableBytes("BGRA color buffer", bgra, width * height * PixelFormat.BGRA32.bytesPerPixel());
        target.clear();
        for (int i = 0; i < width * height; i++) {
            int base = i * 4;
            target.put(bgra.get(base));
            target.put(bgra.get(base + 1));
            target.put(bgra.get(base + 2));
        }
        target.flip();
    }

    private void extractAlphaMask(ByteBuffer bgra, int width, int height, ByteBuffer target) {
        verifyReadableBytes("BGRA alpha buffer", bgra, width * height * PixelFormat.BGRA32.bytesPerPixel());
        target.clear();
        for (int i = 0; i < width * height; i++) {
            int alpha = Byte.toUnsignedInt(bgra.get((i * 4) + 3));
            byte value = (byte) alpha;
            target.put(value);
            target.put(value);
            target.put(value);
        }
        target.flip();
    }

    private float mapDepth(float z) {
        if (Float.isNaN(z) || Float.isInfinite(z)) {
            return 1.0F;
        }
        float clamped = Math.max(0.0F, Math.min(1.0F, z));
        float inverted = 1.0F - clamped;
        float curved = (float) Math.sqrt(inverted);
        return Math.max(0.0F, Math.min(1.0F, curved));
    }

    private static void resetNativeReadBuffer(ByteBuffer buffer, int expectedBytes) {
        buffer.position(0);
        buffer.limit(Math.min(buffer.capacity(), expectedBytes));
    }

    private static void resetNativeReadBuffer(FloatBuffer buffer, int expectedFloats) {
        buffer.position(0);
        buffer.limit(Math.min(buffer.capacity(), expectedFloats));
    }

    private static void verifyReadableBytes(String label, ByteBuffer buffer, int expectedBytes) {
        if (buffer == null) {
            throw new IllegalStateException(label + " is unavailable");
        }
        if (buffer.limit() < expectedBytes) {
            throw new IllegalStateException(label + " has insufficient readable bytes: limit=" + buffer.limit() + ", expected=" + expectedBytes);
        }
    }

    private static ByteBuffer ensureByteBuffer(ByteBuffer existing, int capacity) {
        if (existing == null || existing.capacity() < capacity) {
            return BufferUtils.createByteBuffer(capacity);
        }
        return existing;
    }

    private static FloatBuffer ensureFloatBuffer(FloatBuffer existing, int capacity) {
        if (existing == null || existing.capacity() < capacity) {
            return BufferUtils.createFloatBuffer(capacity);
        }
        return existing;
    }

    private static final class DirectByteBufferPool {
        private final Map<BufferKey, ConcurrentLinkedDeque<ByteBuffer>> buffers = new ConcurrentHashMap<>();

        ByteBuffer borrow(int width, int height, PixelFormat format) {
            BufferKey key = new BufferKey(width, height, format);
            ConcurrentLinkedDeque<ByteBuffer> queue = buffers.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
            ByteBuffer buffer = queue.pollFirst();
            if (buffer == null) {
                buffer = BufferUtils.createByteBuffer(key.capacityBytes());
            }
            buffer.clear();
            return buffer;
        }

        void release(int width, int height, PixelFormat format, ByteBuffer buffer) {
            BufferKey key = new BufferKey(width, height, format);
            buffer.clear();
            buffers.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>()).offerFirst(buffer);
        }
    }

    private record BufferKey(int width, int height, PixelFormat format) {
        private BufferKey {
            Objects.requireNonNull(format, "format");
        }

        int capacityBytes() {
            return width * height * format.bytesPerPixel();
        }
    }
}
