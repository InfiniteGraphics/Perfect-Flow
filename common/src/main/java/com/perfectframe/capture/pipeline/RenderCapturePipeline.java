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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class RenderCapturePipeline {
    private ByteBuffer reusableBgrBuffer;
    private ByteBuffer reusableBgraBuffer;
    private ByteBuffer reusableColorWorkingBgrBuffer;
    private ByteBuffer reusableMotionBlurOutputBgrBuffer;
    private ByteBuffer reusableAlphaMaskBgrBuffer;
    private FloatBuffer reusableDepthFloatBuffer;
    private ByteBuffer reusableDepthBgrBuffer;
    private final Deque<ByteBuffer> frameBlendHistory = new ArrayDeque<>();

    public List<CapturedFrame> capture(CaptureSession session, CaptureSource source) {
        PerfectFrameConfig config = session.config();
        int width = source.width();
        int height = source.height();
        session.setCaptureSize(width, height);
        List<CapturedFrame> frames = new ArrayList<>();

        boolean needsColor = config.capture.recordColor || config.capture.recordAlpha;
        if (needsColor) {
            ByteBuffer bgra = null;
            ByteBuffer colorPixels = null;
            try {
                if (config.capture.recordAlpha || config.motionBlur.enabled) {
                    bgra = readColor(source.colorAttachment(), width, height, PixelFormat.BGRA32, reusableBgraBuffer);
                    reusableBgraBuffer = bgra;
                }

                if (config.capture.recordColor) {
                    if (config.motionBlur.enabled) {
                        colorPixels = applyMotionBlur(config, width, height, bgra);
                    } else if (bgra != null) {
                        colorPixels = extractColorBgr(bgra, width, height, obtainReusableColorWorkingBgr(width, height));
                    } else {
                        colorPixels = readColor(source.colorAttachment(), width, height, PixelFormat.BGR24, reusableBgrBuffer);
                        reusableBgrBuffer = colorPixels;
                    }
                    frames.add(new CapturedFrame("color", session.capturedFrames(), width, height, PixelFormat.BGR24, copyFrame(colorPixels)));
                } else {
                    frameBlendHistory.clear();
                }

                if (config.capture.recordAlpha) {
                    ByteBuffer alphaMask = extractAlphaMask(bgra, width, height, obtainReusableAlphaMaskBgr(width, height));
                    frames.add(new CapturedFrame("alpha", session.capturedFrames(), width, height, PixelFormat.ALPHA_MASK_BGR24, copyFrame(alphaMask)));
                }
            } finally {
                if (!config.capture.recordColor) {
                    frameBlendHistory.clear();
                }
            }
        } else {
            frameBlendHistory.clear();
        }

        if (config.capture.recordDepth) {
            ByteBuffer depthPixels = readDepth(source.depthAttachment(), width, height);
            frames.add(new CapturedFrame("depth", session.capturedFrames(), width, height, PixelFormat.DEPTH_BGR24, copyFrame(depthPixels)));
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

    private ByteBuffer readDepth(CaptureAttachment attachment, int width, int height) {
        int expectedPixels = width * height;
        reusableDepthFloatBuffer = ensureFloatBuffer(reusableDepthFloatBuffer, expectedPixels);
        reusableDepthFloatBuffer.clear();
        reusableDepthBgrBuffer = ensureByteBuffer(reusableDepthBgrBuffer, expectedPixels * PixelFormat.DEPTH_BGR24.bytesPerPixel());
        reusableDepthBgrBuffer.clear();
        attachment.bindRead();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, reusableDepthFloatBuffer);
        attachment.unbindRead();
        resetNativeReadBuffer(reusableDepthFloatBuffer, expectedPixels);
        while (reusableDepthFloatBuffer.hasRemaining()) {
            float normalized = mapDepth(reusableDepthFloatBuffer.get());
            byte value = (byte) Math.round(normalized * 255.0F);
            reusableDepthBgrBuffer.put(value);
            reusableDepthBgrBuffer.put(value);
            reusableDepthBgrBuffer.put(value);
        }
        reusableDepthBgrBuffer.flip();
        return reusableDepthBgrBuffer;
    }

    private ByteBuffer applyMotionBlur(PerfectFrameConfig config, int width, int height, ByteBuffer bgra) {
        return switch (config.motionBlur.mode) {
            case FRAME_BLEND -> applyFrameBlend(width, height, bgra, config.motionBlur);
            case ACCUMULATION -> applyAccumulation(width, height, bgra, config.motionBlur);
        };
    }

    private ByteBuffer applyFrameBlend(int width, int height, ByteBuffer bgra, PerfectFrameConfig.MotionBlur settings) {
        ByteBuffer currentBgr = extractColorBgr(bgra, width, height, obtainReusableColorWorkingBgr(width, height));
        ByteBuffer currentCopy = copyFrame(currentBgr);
        frameBlendHistory.addLast(currentCopy);
        while (frameBlendHistory.size() > settings.blendFrameCount) {
            frameBlendHistory.removeFirst();
        }

        ByteBuffer output = obtainReusableMotionBlurOutputBgr(width, height);
        output.clear();
        int pixels = width * height;
        double strength = settings.shutterFraction;
        int historySize = frameBlendHistory.size();
        for (int i = 0; i < pixels; i++) {
            double totalWeight = 0.0D;
            double blue = 0.0D;
            double green = 0.0D;
            double red = 0.0D;
            int index = 0;
            for (ByteBuffer history : frameBlendHistory) {
                int base = i * 3;
                double t = historySize <= 1 ? 0.0D : index / (double) (historySize - 1);
                double weight = 1.0D - strength + (strength * (t + 1.0D / historySize));
                totalWeight += weight;
                blue += Byte.toUnsignedInt(history.get(base)) * weight;
                green += Byte.toUnsignedInt(history.get(base + 1)) * weight;
                red += Byte.toUnsignedInt(history.get(base + 2)) * weight;
                index++;
            }
            output.put((byte) Math.round(blue / totalWeight));
            output.put((byte) Math.round(green / totalWeight));
            output.put((byte) Math.round(red / totalWeight));
        }
        output.flip();
        return output;
    }

    private ByteBuffer applyAccumulation(int width, int height, ByteBuffer bgra, PerfectFrameConfig.MotionBlur settings) {
        ByteBuffer baseBgr = extractColorBgr(bgra, width, height, obtainReusableColorWorkingBgr(width, height));
        ByteBuffer output = obtainReusableMotionBlurOutputBgr(width, height);
        output.clear();
        int pixels = width * height;
        int samples = Math.max(1, settings.sampleCount);
        double shutterWeight = Math.max(0.0D, Math.min(1.0D, settings.shutterFraction));
        for (int i = 0; i < pixels; i++) {
            int base = i * 3;
            int b = Byte.toUnsignedInt(baseBgr.get(base));
            int g = Byte.toUnsignedInt(baseBgr.get(base + 1));
            int r = Byte.toUnsignedInt(baseBgr.get(base + 2));
            double accumulationFactor = 0.82D + (0.18D * shutterWeight);
            int blue = (int) Math.round((b * accumulationFactor * samples) / samples);
            int green = (int) Math.round((g * accumulationFactor * samples) / samples);
            int red = (int) Math.round((r * accumulationFactor * samples) / samples);
            output.put((byte) Math.max(0, Math.min(255, blue)));
            output.put((byte) Math.max(0, Math.min(255, green)));
            output.put((byte) Math.max(0, Math.min(255, red)));
        }
        output.flip();
        return output;
    }

    private ByteBuffer extractColorBgr(ByteBuffer bgra, int width, int height, ByteBuffer target) {
        verifyReadableBytes("BGRA color buffer", bgra, width * height * PixelFormat.BGRA32.bytesPerPixel());
        target.clear();
        for (int i = 0; i < width * height; i++) {
            int base = i * 4;
            target.put(bgra.get(base));
            target.put(bgra.get(base + 1));
            target.put(bgra.get(base + 2));
        }
        target.flip();
        return target;
    }

    private ByteBuffer extractAlphaMask(ByteBuffer bgra, int width, int height, ByteBuffer target) {
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
        return target;
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

    private ByteBuffer obtainReusableBgr(int width, int height) {
        reusableBgrBuffer = ensureByteBuffer(reusableBgrBuffer, width * height * PixelFormat.BGR24.bytesPerPixel());
        return reusableBgrBuffer;
    }

    private ByteBuffer obtainReusableColorWorkingBgr(int width, int height) {
        reusableColorWorkingBgrBuffer = ensureByteBuffer(reusableColorWorkingBgrBuffer, width * height * PixelFormat.BGR24.bytesPerPixel());
        return reusableColorWorkingBgrBuffer;
    }

    private ByteBuffer obtainReusableMotionBlurOutputBgr(int width, int height) {
        reusableMotionBlurOutputBgrBuffer = ensureByteBuffer(reusableMotionBlurOutputBgrBuffer, width * height * PixelFormat.BGR24.bytesPerPixel());
        return reusableMotionBlurOutputBgrBuffer;
    }

    private ByteBuffer obtainReusableAlphaMaskBgr(int width, int height) {
        reusableAlphaMaskBgrBuffer = ensureByteBuffer(reusableAlphaMaskBgrBuffer, width * height * PixelFormat.ALPHA_MASK_BGR24.bytesPerPixel());
        return reusableAlphaMaskBgrBuffer;
    }

    private ByteBuffer copyFrame(ByteBuffer source) {
        ByteBuffer copy = BufferUtils.createByteBuffer(source.remaining());
        ByteBuffer duplicate = source.duplicate();
        copy.put(duplicate);
        copy.flip();
        return copy;
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
}
