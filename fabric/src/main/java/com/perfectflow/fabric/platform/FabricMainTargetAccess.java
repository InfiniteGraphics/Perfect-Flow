package com.perfectflow.fabric.platform;

import com.perfectflow.platform.services.MainTargetAccess;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public final class FabricMainTargetAccess implements MainTargetAccess {
    private final Framebuffer framebuffer;

    public FabricMainTargetAccess(Framebuffer framebuffer) {
        this.framebuffer = framebuffer;
    }

    @Override
    public int width() {
        return framebuffer.textureWidth;
    }

    @Override
    public int height() {
        return framebuffer.textureHeight;
    }

    @Override
    public void bindReadForColor() {
        framebuffer.beginRead();
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
    }

    @Override
    public void bindReadForDepth() {
        framebuffer.beginRead();
        GL11.glReadBuffer(GL11.GL_NONE);
    }

    @Override
    public void unbindRead() {
        framebuffer.endRead();
    }
}
