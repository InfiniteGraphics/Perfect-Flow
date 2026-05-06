package com.perfectframe.capture;

public final class FrameScheduler {
    private final int targetFps;
    private final double engineSpeed;
    private long frameIndex;

    public FrameScheduler(int targetFps, double engineSpeed) {
        this.targetFps = Math.max(1, targetFps);
        this.engineSpeed = Math.max(0.01D, engineSpeed);
    }

    public int targetFps() {
        return targetFps;
    }

    public long frameIndex() {
        return frameIndex;
    }

    public double videoSeconds() {
        return frameIndex / (double) targetFps;
    }

    public double virtualGameSeconds() {
        return videoSeconds() * engineSpeed;
    }

    public float fixedPartialTick() {
        double ticks = virtualGameSeconds() * 20.0D;
        return (float) (ticks - Math.floor(ticks));
    }

    public void advance() {
        frameIndex++;
    }
}
