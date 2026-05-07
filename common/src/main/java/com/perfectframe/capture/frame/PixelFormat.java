package com.perfectframe.capture.frame;

public enum PixelFormat {
    BGR24(3, "bgr24", 24),
    BGRA32(4, "bgra", 32),
    ALPHA_MASK_BGR24(3, "bgr24", 24),
    DEPTH_BGR24(3, "bgr24", 24);

    private final int bytesPerPixel;
    private final String ffmpegName;
    private final int tgaBitsPerPixel;

    PixelFormat(int bytesPerPixel, String ffmpegName, int tgaBitsPerPixel) {
        this.bytesPerPixel = bytesPerPixel;
        this.ffmpegName = ffmpegName;
        this.tgaBitsPerPixel = tgaBitsPerPixel;
    }

    public int bytesPerPixel() {
        return bytesPerPixel;
    }

    public String ffmpegName() {
        return ffmpegName;
    }

    public int tgaBitsPerPixel() {
        return tgaBitsPerPixel;
    }
}
