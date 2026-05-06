package com.perfectframe.neoforge.platform;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.perfectframe.platform.services.MainTargetAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public final class NeoForgeMainTargetAccess implements MainTargetAccess {
    private final RenderTarget target;

    public NeoForgeMainTargetAccess(RenderTarget target) {
        this.target = target;
    }

    @Override
    public int width() {
        return target.width;
    }

    @Override
    public int height() {
        return target.height;
    }

    @Override
    public void bindReadForColor() {
        target.bindRead();
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
    }

    @Override
    public void bindReadForDepth() {
        target.bindRead();
        GL11.glReadBuffer(GL11.GL_NONE);
    }

    @Override
    public void unbindRead() {
        target.unbindRead();
    }
}
