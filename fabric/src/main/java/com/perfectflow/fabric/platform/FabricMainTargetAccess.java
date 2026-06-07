package com.perfectflow.fabric.platform;

import com.perfectflow.platform.services.MainTargetAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public final class FabricMainTargetAccess implements MainTargetAccess {
    private final RenderTarget framebuffer;

    public FabricMainTargetAccess(RenderTarget framebuffer) {
        this.framebuffer = framebuffer;
    }

    @Override
    public int width() {
        return framebuffer.width;
    }

    @Override
    public int height() {
        return framebuffer.height;
    }

    @Override
    public void bindReadForColor() {
        framebuffer.bindRead();
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
    }

    @Override
    public void bindReadForDepth() {
        framebuffer.bindRead();
        GL11.glReadBuffer(GL11.GL_NONE);
    }

    @Override
    public void unbindRead() {
        framebuffer.unbindRead();
    }
}
