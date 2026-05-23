package com.perfectflow.capture.pipeline;

import com.perfectflow.capture.CaptureSession;
import com.perfectflow.capture.frame.CapturedFrame;
import com.perfectflow.capture.frame.PixelFormat;
import com.perfectflow.config.PerfectFlowConfig;
import com.perfectflow.shader.CaptureAttachment;
import com.perfectflow.shader.CaptureSource;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

public final class RenderCapturePipeline {
    private FloatBuffer reusableDepthFloatBuffer;
    private final DirectByteBufferPool framePool = new DirectByteBufferPool();
    private final PboReadbackRing pboReadbackRing = new PboReadbackRing();
    private CaptureSession activeSession;
    private int activeCaptureWidth = -1;
    private int activeCaptureHeight = -1;
    private String activeCaptureSourceId;

    public List<CapturedFrame> capture(CaptureSession session, CaptureSource source) {
        PerfectFlowConfig config = session.config();
        int width = source.width();
        int height = source.height();
        session.setCaptureSize(width, height);
        if (session != activeSession || width != activeCaptureWidth || height != activeCaptureHeight || !Objects.equals(activeCaptureSourceId, source.id())) {
            activeSession = session;
            activeCaptureWidth = width;
            activeCaptureHeight = height;
            activeCaptureSourceId = source.id();
            pboReadbackRing.clear();
        }
        List<CapturedFrame> frames = new ArrayList<>();

        boolean needsColor = config.capture.recordColor || config.capture.recordAlpha;
        if (needsColor) {
            ByteBuffer bgra = null;
            if (config.capture.recordColor && config.capture.recordAlpha) {
                bgra = borrowFrameBuffer(width, height, PixelFormat.BGRA32);
                readColor(source.colorAttachment(), width, height, PixelFormat.BGRA32, bgra);
            } else if (config.capture.recordAlpha) {
                bgra = borrowFrameBuffer(width, height, PixelFormat.BGRA32);
                readColor(source.colorAttachment(), width, height, PixelFormat.BGRA32, bgra);
            }

            if (config.capture.recordColor) {
                ByteBuffer colorPixels = borrowFrameBuffer(width, height, PixelFormat.BGR24);
                if (bgra != null) {
                    extractColorBgr(bgra, width, height, colorPixels);
                } else {
                    readColor(source.colorAttachment(), width, height, PixelFormat.BGR24, colorPixels);
                }
                ByteBuffer colorFrame = colorPixels;
                frames.add(new CapturedFrame("color", session.capturedFrames(), width, height, PixelFormat.BGR24, colorFrame,
                        () -> releaseFrameBuffer(width, height, PixelFormat.BGR24, colorFrame)));
            }

            if (config.capture.recordAlpha) {
                ByteBuffer alphaMask = borrowFrameBuffer(width, height, PixelFormat.ALPHA_MASK_BGR24);
                extractAlphaMask(bgra, width, height, alphaMask);
                frames.add(new CapturedFrame("alpha", session.capturedFrames(), width, height, PixelFormat.ALPHA_MASK_BGR24, alphaMask,
                        () -> releaseFrameBuffer(width, height, PixelFormat.ALPHA_MASK_BGR24, alphaMask)));
                releaseFrameBuffer(width, height, PixelFormat.BGRA32, bgra);
            }
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
        PboReadbackKey key = PboReadbackKey.bytes(width, height, format.bytesPerPixel());
        ByteBuffer pixels = ensureByteBuffer(reusableBuffer, expectedBytes);
        if (!pboReadbackRing.harvestBytes(key, pixels)) {
            directReadColor(attachment, width, height, format, pixels);
        }
        pboReadbackRing.queueBytes(key, () -> readColorIntoPbo(attachment, width, height, format));
        return pixels;
    }

    private void readDepth(CaptureAttachment attachment, int width, int height, ByteBuffer output) {
        int expectedPixels = width * height;
        reusableDepthFloatBuffer = ensureFloatBuffer(reusableDepthFloatBuffer, expectedPixels);
        PboReadbackKey key = PboReadbackKey.floats(width, height);
        if (!pboReadbackRing.harvestFloats(key, reusableDepthFloatBuffer)) {
            directReadDepth(attachment, width, height, reusableDepthFloatBuffer);
        }
        pboReadbackRing.queueFloats(key, () -> readDepthIntoPbo(attachment, width, height));
        while (reusableDepthFloatBuffer.hasRemaining()) {
            float normalized = mapDepth(reusableDepthFloatBuffer.get());
            byte value = (byte) Math.round(normalized * 255.0F);
            output.put(value);
            output.put(value);
            output.put(value);
        }
        output.flip();
    }

    private ByteBuffer borrowFrameBuffer(int width, int height, PixelFormat format) {
        return framePool.borrow(width, height, format);
    }

    private void releaseFrameBuffer(int width, int height, PixelFormat format, ByteBuffer buffer) {
        framePool.release(width, height, format, buffer);
    }

    private void directReadColor(CaptureAttachment attachment, int width, int height, PixelFormat format, ByteBuffer output) {
        output.clear();
        attachment.bindRead();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        int glFormat = format == PixelFormat.BGRA32 ? GL12.GL_BGRA : GL12.GL_BGR;
        GL11.glReadPixels(0, 0, width, height, glFormat, GL11.GL_UNSIGNED_BYTE, output);
        attachment.unbindRead();
        resetNativeReadBuffer(output, width * height * format.bytesPerPixel());
    }

    private void readColorIntoPbo(CaptureAttachment attachment, int width, int height, PixelFormat format) {
        attachment.bindRead();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        int glFormat = format == PixelFormat.BGRA32 ? GL12.GL_BGRA : GL12.GL_BGR;
        GL11.glReadPixels(0, 0, width, height, glFormat, GL11.GL_UNSIGNED_BYTE, 0L);
        attachment.unbindRead();
    }

    private void directReadDepth(CaptureAttachment attachment, int width, int height, FloatBuffer output) {
        output.clear();
        attachment.bindRead();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, output);
        attachment.unbindRead();
        resetNativeReadBuffer(output, width * height);
    }

    private void readDepthIntoPbo(CaptureAttachment attachment, int width, int height) {
        attachment.bindRead();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 0L);
        attachment.unbindRead();
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

    private static final class PboReadbackRing {
        private static final int SLOT_COUNT = 4;
        private static final long SHORT_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(1L);

        private final Map<PboReadbackKey, State> states = new HashMap<>();

        boolean harvestBytes(PboReadbackKey key, ByteBuffer target) {
            return harvestBytesInternal(key, target);
        }

        boolean harvestFloats(PboReadbackKey key, FloatBuffer target) {
            return harvestFloatsInternal(key, target);
        }

        boolean queueBytes(PboReadbackKey key, Runnable writer) {
            return queue(key, writer);
        }

        boolean queueFloats(PboReadbackKey key, Runnable writer) {
            return queue(key, writer);
        }

        void clear() {
            for (State state : states.values()) {
                state.destroy();
            }
            states.clear();
        }

        private boolean harvestBytesInternal(PboReadbackKey key, ByteBuffer target) {
            State state = states.get(key);
            if (state == null || state.pendingCount == 0 || state.failed) {
                return false;
            }
            int previousBinding = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            try {
                while (state.pendingCount > 0) {
                    int slot = state.readIndex;
                    long fence = state.fences[slot];
                    if (fence == 0L) {
                        return false;
                    }
                    int waitResult = GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
                    if (waitResult == GL32.GL_TIMEOUT_EXPIRED) {
                        return false;
                    }
                    if (waitResult == GL32.GL_WAIT_FAILED) {
                        state.failed = true;
                        state.destroy();
                        states.remove(key);
                        return false;
                    }

                    GL32.glDeleteSync(fence);
                    state.fences[slot] = 0L;
                    state.readIndex = (slot + 1) % SLOT_COUNT;
                    state.pendingCount--;
                    if (state.warmupDiscardRemaining > 0) {
                        state.warmupDiscardRemaining--;
                        continue;
                    }

                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, state.pbos[slot]);
                    target.clear();
                    GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0L, target);
                    resetNativeReadBuffer(target, key.sizeBytes());
                    return true;
                }
                return false;
            } finally {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousBinding);
            }
        }

        private boolean harvestFloatsInternal(PboReadbackKey key, FloatBuffer target) {
            State state = states.get(key);
            if (state == null || state.pendingCount == 0 || state.failed) {
                return false;
            }
            int previousBinding = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            try {
                while (state.pendingCount > 0) {
                    int slot = state.readIndex;
                    long fence = state.fences[slot];
                    if (fence == 0L) {
                        return false;
                    }
                    int waitResult = GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
                    if (waitResult == GL32.GL_TIMEOUT_EXPIRED) {
                        return false;
                    }
                    if (waitResult == GL32.GL_WAIT_FAILED) {
                        state.failed = true;
                        state.destroy();
                        states.remove(key);
                        return false;
                    }

                    GL32.glDeleteSync(fence);
                    state.fences[slot] = 0L;
                    state.readIndex = (slot + 1) % SLOT_COUNT;
                    state.pendingCount--;
                    if (state.warmupDiscardRemaining > 0) {
                        state.warmupDiscardRemaining--;
                        continue;
                    }

                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, state.pbos[slot]);
                    target.clear();
                    GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0L, target);
                    resetNativeReadBuffer(target, key.sizeBytes() / Float.BYTES);
                    return true;
                }
                return false;
            } finally {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousBinding);
            }
        }

        private boolean queue(PboReadbackKey key, Runnable writer) {
            State state = states.computeIfAbsent(key, ignored -> new State(key));
            if (state.failed) {
                return false;
            }
            if (state.pendingCount >= SLOT_COUNT) {
                int oldestSlot = state.readIndex;
                long fence = state.fences[oldestSlot];
                if (fence == 0L) {
                    return false;
                }
                int waitResult = GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, SHORT_WAIT_NANOS);
                if (waitResult == GL32.GL_WAIT_FAILED) {
                    state.failed = true;
                    state.destroy();
                    states.remove(key);
                    return false;
                }
                if (waitResult == GL32.GL_TIMEOUT_EXPIRED) {
                    return false;
                }
                GL32.glDeleteSync(fence);
                state.fences[oldestSlot] = 0L;
                state.readIndex = (oldestSlot + 1) % SLOT_COUNT;
                state.pendingCount--;
            }

            int slot = state.writeIndex;
            int previousBinding = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, state.pbos[slot]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, key.sizeBytes(), GL15.GL_STREAM_READ);
                writer.run();
                long sync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                if (state.fences[slot] != 0L) {
                    GL32.glDeleteSync(state.fences[slot]);
                }
                state.fences[slot] = sync;
                state.writeIndex = (slot + 1) % SLOT_COUNT;
                state.pendingCount++;
                return true;
            } finally {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousBinding);
            }
        }

        private static final class State {
            private final PboReadbackKey key;
            private final int[] pbos = new int[SLOT_COUNT];
            private final long[] fences = new long[SLOT_COUNT];
            private int readIndex;
            private int writeIndex;
            private int pendingCount;
            private int warmupDiscardRemaining = SLOT_COUNT - 1;
            private boolean failed;

            private State(PboReadbackKey key) {
                this.key = key;
                int previousBinding = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
                for (int i = 0; i < SLOT_COUNT; i++) {
                    pbos[i] = GL15.glGenBuffers();
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbos[i]);
                    GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, key.sizeBytes(), GL15.GL_STREAM_READ);
                }
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousBinding);
            }

            private void destroy() {
                for (int i = 0; i < SLOT_COUNT; i++) {
                    if (fences[i] != 0L) {
                        GL32.glDeleteSync(fences[i]);
                        fences[i] = 0L;
                    }
                    if (pbos[i] != 0) {
                        GL15.glDeleteBuffers(pbos[i]);
                        pbos[i] = 0;
                    }
                }
            }
        }
    }

    private record PboReadbackKey(int width, int height, int bytesPerElement, ReadbackKind kind) {
        private PboReadbackKey {
            Objects.requireNonNull(kind, "kind");
        }

        static PboReadbackKey bytes(int width, int height, int bytesPerElement) {
            return new PboReadbackKey(width, height, bytesPerElement, ReadbackKind.BYTES);
        }

        static PboReadbackKey floats(int width, int height) {
            return new PboReadbackKey(width, height, Float.BYTES, ReadbackKind.FLOATS);
        }

        int sizeBytes() {
            return width * height * bytesPerElement;
        }
    }

    private enum ReadbackKind {
        BYTES,
        FLOATS
    }
}
