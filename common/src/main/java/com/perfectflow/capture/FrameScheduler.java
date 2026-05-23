package com.perfectflow.capture;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public final class FrameScheduler {
    private final int targetFps;
    private final double engineSpeed;
    private final long framePeriodNanos;
    private long frameIndex;
    private long startNanos = -1L;
    private long nextFrameDeadlineNanos = -1L;

    public FrameScheduler(int targetFps, double engineSpeed) {
        this.targetFps = Math.max(1, targetFps);
        this.engineSpeed = Math.max(0.01D, engineSpeed);
        this.framePeriodNanos = Math.max(1L, TimeUnit.SECONDS.toNanos(1L) / this.targetFps);
    }

    public int targetFps() {
        return targetFps;
    }

    public long frameIndex() {
        return frameIndex;
    }

    public void begin() {
        startNanos = System.nanoTime();
        nextFrameDeadlineNanos = startNanos;
    }

    public void awaitFrameWindow(boolean paced) {
        if (!paced) {
            return;
        }
        if (nextFrameDeadlineNanos < 0L) {
            begin();
        }
        long now = System.nanoTime();
        if (now > nextFrameDeadlineNanos + framePeriodNanos) {
            nextFrameDeadlineNanos = now;
        }
        long delay = nextFrameDeadlineNanos - now;
        if (delay > 0L) {
            LockSupport.parkNanos(delay);
        }
    }

    public double videoSeconds() {
        return frameIndex / (double) targetFps;
    }

    public double virtualGameSeconds() {
        return elapsedSeconds() * engineSpeed;
    }

    public float fixedPartialTick() {
        double ticks = virtualGameSeconds() * 20.0D;
        return (float) (ticks - Math.floor(ticks));
    }

    public void advance() {
        frameIndex++;
        if (nextFrameDeadlineNanos >= 0L) {
            nextFrameDeadlineNanos += framePeriodNanos;
        }
    }

    public double elapsedSeconds() {
        if (startNanos < 0L) {
            return 0.0D;
        }
        return (System.nanoTime() - startNanos) / 1_000_000_000.0D;
    }
}
