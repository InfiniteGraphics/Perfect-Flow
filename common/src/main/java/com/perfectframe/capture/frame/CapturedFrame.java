package com.perfectframe.capture.frame;

import java.nio.ByteBuffer;

public final class CapturedFrame {
    private final String streamName;
    private final long frameIndex;
    private final int width;
    private final int height;
    private final PixelFormat format;
    private final ByteBuffer pixels;

    public CapturedFrame(String streamName, long frameIndex, int width, int height, PixelFormat format, ByteBuffer pixels) {
        this.streamName = streamName;
        this.frameIndex = frameIndex;
        this.width = width;
        this.height = height;
        this.format = format;
        this.pixels = pixels;
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
}
