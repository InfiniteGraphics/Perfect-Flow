package com.perfectflow.capture.frame;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CapturedFrame {
    private final String streamName;
    private final long frameIndex;
    private final int width;
    private final int height;
    private final PixelFormat format;
    private final ByteBuffer pixels;
    private final Runnable releaseAction;
    private final AtomicBoolean released = new AtomicBoolean();

    public CapturedFrame(String streamName, long frameIndex, int width, int height, PixelFormat format, ByteBuffer pixels) {
        this(streamName, frameIndex, width, height, format, pixels, () -> {
        });
    }

    public CapturedFrame(String streamName, long frameIndex, int width, int height, PixelFormat format, ByteBuffer pixels, Runnable releaseAction) {
        this.streamName = streamName;
        this.frameIndex = frameIndex;
        this.width = width;
        this.height = height;
        this.format = format;
        this.pixels = Objects.requireNonNull(pixels, "pixels");
        this.releaseAction = Objects.requireNonNull(releaseAction, "releaseAction");
    }

    public String streamName() {
        return streamName;
    }

    public long frameIndex() {
        return frameIndex;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public PixelFormat format() {
        return format;
    }

    public ByteBuffer pixels() {
        return pixels.asReadOnlyBuffer();
    }

    public void release() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        releaseAction.run();
    }
}
