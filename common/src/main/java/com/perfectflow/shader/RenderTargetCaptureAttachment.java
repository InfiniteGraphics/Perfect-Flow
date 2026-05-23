package com.perfectflow.shader;

import com.perfectflow.platform.services.MainTargetAccess;

public final class RenderTargetCaptureAttachment implements CaptureAttachment {
    private final MainTargetAccess target;
    private final boolean depth;

    public RenderTargetCaptureAttachment(MainTargetAccess target) {
        this(target, false);
    }

    public RenderTargetCaptureAttachment(MainTargetAccess target, boolean depth) {
        this.target = target;
        this.depth = depth;
    }

    @Override
    public int width() {
        return target.width();
    }

    @Override
    public int height() {
        return target.height();
    }

    @Override
    public void bindRead() {
        if (depth) {
            target.bindReadForDepth();
        } else {
            target.bindReadForColor();
        }
    }

    @Override
    public void unbindRead() {
        target.unbindRead();
    }
}
