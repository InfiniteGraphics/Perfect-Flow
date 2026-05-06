package com.perfectframe.shader;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public final class DepthTextureCaptureAttachment implements CaptureAttachment {
    private final int textureId;
    private final int width;
    private final int height;
    private int readFramebufferId;
    private int previousReadFramebufferId;

    public DepthTextureCaptureAttachment(int textureId, int width, int height) {
        this.textureId = textureId;
        this.width = width;
        this.height = height;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public void bindRead() {
        previousReadFramebufferId = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        readFramebufferId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebufferId);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, textureId, 0);
        GL11.glReadBuffer(GL11.GL_NONE);
    }

    @Override
    public void unbindRead() {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebufferId);
        if (readFramebufferId != 0) {
            GL30.glDeleteFramebuffers(readFramebufferId);
            readFramebufferId = 0;
        }
    }
}
