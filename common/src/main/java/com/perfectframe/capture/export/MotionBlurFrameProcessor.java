package com.perfectframe.capture.export;

import com.perfectframe.capture.frame.CapturedFrame;
import com.perfectframe.capture.frame.PixelFormat;
import com.perfectframe.config.PerfectFlowConfig;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

final class MotionBlurFrameProcessor {
    private static final int MAX_HISTORY = 16;
    private static final float MIN_ACCUMULATION_ALPHA = 0.02F;

    private final boolean enabled;
    private final PerfectFlowConfig.MotionBlurMode mode;
    private final double shutterFraction;
    private final int sampleCount;
    private final int blendFrameCount;

    private int width = -1;
    private int height = -1;
    private ByteBuffer[] frameBlendBuffers = new ByteBuffer[0];
    private int frameBlendStart;
    private int frameBlendCount;
    private ByteBuffer frameBlendOutputBuffer;
    private ByteBuffer accumulationBuffer;
    private ByteBuffer accumulationScratchBuffer;
    private boolean accumulationInitialized;

    MotionBlurFrameProcessor(PerfectFlowConfig config, String streamName) {
        PerfectFlowConfig.MotionBlur settings = config.motionBlur;
        this.enabled = settings != null && settings.enabled && "color".equals(streamName);
        this.mode = settings == null ? PerfectFlowConfig.MotionBlurMode.FRAME_BLEND : settings.mode;
        this.shutterFraction = settings == null ? 0.5D : settings.shutterFraction;
        this.sampleCount = settings == null ? 4 : settings.sampleCount;
        this.blendFrameCount = settings == null ? 4 : settings.blendFrameCount;
    }

    ByteBuffer process(CapturedFrame frame) {
        if (frame == null) {
            return null;
        }
        if (!enabled || frame.format() != PixelFormat.BGR24) {
            return frame.pixels();
        }

        ensureDimensions(frame.width(), frame.height());
        return mode == PerfectFlowConfig.MotionBlurMode.ACCUMULATION
                ? applyAccumulation(frame)
                : applyFrameBlend(frame);
    }

    void reset() {
        width = -1;
        height = -1;
        frameBlendBuffers = new ByteBuffer[0];
        frameBlendStart = 0;
        frameBlendCount = 0;
        frameBlendOutputBuffer = null;
        accumulationBuffer = null;
        accumulationScratchBuffer = null;
        accumulationInitialized = false;
    }

    private ByteBuffer applyFrameBlend(CapturedFrame frame) {
        int bytes = width * height * PixelFormat.BGR24.bytesPerPixel();
        ByteBuffer source = frame.pixels();
        ByteBuffer historySlot = obtainHistorySlot(bytes);
        copyBuffer(source, historySlot);

        ByteBuffer output = ensureByteBuffer(frameBlendOutputBuffer, bytes);
        frameBlendOutputBuffer = output;
        output.clear();

        int pixels = width * height;
        double strength = clamp(shutterFraction, 0.0D, 1.0D);
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
        return output;
    }

    private ByteBuffer applyAccumulation(CapturedFrame frame) {
        int bytes = width * height * PixelFormat.BGR24.bytesPerPixel();
        ByteBuffer source = frame.pixels();
        if (!accumulationInitialized) {
            accumulationBuffer = ensureByteBuffer(accumulationBuffer, bytes);
            copyBuffer(source, accumulationBuffer);
            accumulationInitialized = true;
            return accumulationBuffer;
        }

        ByteBuffer previous = accumulationBuffer;
        ByteBuffer output = ensureByteBuffer(accumulationScratchBuffer, bytes);
        accumulationScratchBuffer = output;
        output.clear();

        float alpha = clamp((float) (shutterFraction / Math.max(1, sampleCount)), MIN_ACCUMULATION_ALPHA, 1.0F);
        float previousWeight = 1.0F - alpha;
        int pixels = width * height;
        for (int i = 0; i < pixels; i++) {
            int base = i * 3;
            int blue = Math.round((Byte.toUnsignedInt(source.get(base)) * alpha) + (Byte.toUnsignedInt(previous.get(base)) * previousWeight));
            int green = Math.round((Byte.toUnsignedInt(source.get(base + 1)) * alpha) + (Byte.toUnsignedInt(previous.get(base + 1)) * previousWeight));
            int red = Math.round((Byte.toUnsignedInt(source.get(base + 2)) * alpha) + (Byte.toUnsignedInt(previous.get(base + 2)) * previousWeight));
            output.put((byte) Math.max(0, Math.min(255, blue)));
            output.put((byte) Math.max(0, Math.min(255, green)));
            output.put((byte) Math.max(0, Math.min(255, red)));
        }
        output.flip();

        accumulationBuffer = output;
        accumulationScratchBuffer = previous;
        return accumulationBuffer;
    }

    private void ensureDimensions(int width, int height) {
        if (this.width == width && this.height == height) {
            return;
        }
        this.width = width;
        this.height = height;
        frameBlendBuffers = new ByteBuffer[0];
        frameBlendStart = 0;
        frameBlendCount = 0;
        frameBlendOutputBuffer = null;
        accumulationBuffer = null;
        accumulationScratchBuffer = null;
        accumulationInitialized = false;
    }

    private ByteBuffer obtainHistorySlot(int bytes) {
        int capacity = Math.max(1, Math.min(MAX_HISTORY, blendFrameCount));
        if (frameBlendBuffers.length != capacity) {
            frameBlendBuffers = new ByteBuffer[capacity];
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

        ByteBuffer slot = ensureByteBuffer(frameBlendBuffers[slotIndex], bytes);
        frameBlendBuffers[slotIndex] = slot;
        slot.clear();
        return slot;
    }

    private static void copyBuffer(ByteBuffer source, ByteBuffer target) {
        target.clear();
        target.put(source.duplicate());
        target.flip();
    }

    private static ByteBuffer ensureByteBuffer(ByteBuffer existing, int capacity) {
        if (existing == null || existing.capacity() < capacity) {
            return BufferUtils.createByteBuffer(capacity);
        }
        return existing;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
