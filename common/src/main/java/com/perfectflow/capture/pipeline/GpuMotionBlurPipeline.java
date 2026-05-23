package com.perfectflow.capture.pipeline;

import com.perfectflow.Constants;
import com.perfectflow.config.PerfectFlowConfig;
import com.perfectflow.shader.CaptureAttachment;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class GpuMotionBlurPipeline {
    private static final int MAX_HISTORY = 16;
    private static final float MIN_ACCUMULATION_ALPHA = 0.02F;

    private final int[] samplerUnits = new int[MAX_HISTORY];
    private final IntBuffer samplerUnitsBuffer = BufferUtils.createIntBuffer(MAX_HISTORY);
    private final FloatBuffer weightsBuffer = BufferUtils.createFloatBuffer(MAX_HISTORY);
    private final IntBuffer viewportBuffer = BufferUtils.createIntBuffer(4);
    private final int[] previousTextureBindings = new int[MAX_HISTORY];

    private boolean failed;
    private int width = -1;
    private int height = -1;
    private int program;
    private int vao;
    private int vbo;
    private int outputFramebuffer;
    private int frameBlendOutputTexture;
    private int accumulationSourceTexture;
    private int[] frameBlendTextures = new int[0];
    private int[] accumulationTextures = new int[0];
    private int frameBlendWriteIndex;
    private int frameBlendCount;
    private int accumulationActiveIndex;
    private boolean accumulationInitialized;
    private int samplerUniformLocation = -1;
    private int weightsUniformLocation = -1;
    private int historySizeUniformLocation = -1;

    public GpuMotionBlurPipeline() {
        for (int i = 0; i < samplerUnits.length; i++) {
            samplerUnits[i] = i;
            samplerUnitsBuffer.put(i);
        }
        samplerUnitsBuffer.flip();
    }

    public boolean render(CaptureAttachment sourceAttachment, int width, int height, PerfectFlowConfig.MotionBlur settings, ByteBuffer output) {
        if (failed || sourceAttachment == null || settings == null || output == null) {
            return false;
        }
        try {
            ensureBaseResources(width, height);
            return switch (settings.mode) {
                case FRAME_BLEND -> renderFrameBlend(sourceAttachment, width, height, settings, output);
                case ACCUMULATION -> renderAccumulation(sourceAttachment, width, height, settings, output);
            };
        } catch (Throwable throwable) {
            failed = true;
            Constants.LOG.warn("GPU motion blur fell back to CPU blur", throwable);
            return false;
        }
    }

    public void reset() {
        frameBlendWriteIndex = 0;
        frameBlendCount = 0;
        accumulationActiveIndex = 0;
        accumulationInitialized = false;
    }

    private boolean renderFrameBlend(CaptureAttachment sourceAttachment, int width, int height, PerfectFlowConfig.MotionBlur settings, ByteBuffer output) {
        int historyCount = Math.max(2, Math.min(MAX_HISTORY, settings.blendFrameCount));
        ensureFrameBlendResources(width, height, historyCount);
        copyAttachmentToTexture(sourceAttachment, frameBlendTextures[frameBlendWriteIndex], width, height);

        if (frameBlendCount < frameBlendTextures.length) {
            frameBlendCount++;
        }

        int nextWriteIndex = (frameBlendWriteIndex + 1) % frameBlendTextures.length;
        int start = frameBlendCount == frameBlendTextures.length ? nextWriteIndex : 0;
        int sampleCount = frameBlendCount;
        int[] textures = new int[sampleCount];
        float[] weights = new float[sampleCount];
        double strength = settings.shutterFraction;
        for (int i = 0; i < sampleCount; i++) {
            int textureIndex = (start + i) % frameBlendTextures.length;
            textures[i] = frameBlendTextures[textureIndex];
            double t = sampleCount <= 1 ? 0.0D : i / (double) (sampleCount - 1);
            weights[i] = (float) (1.0D - strength + (strength * (t + 1.0D / sampleCount)));
        }

        frameBlendWriteIndex = nextWriteIndex;
        renderToTexture(textures, weights, sampleCount, frameBlendOutputTexture, width, height);
        readTextureToBuffer(frameBlendOutputTexture, output, width, height);
        return true;
    }

    private boolean renderAccumulation(CaptureAttachment sourceAttachment, int width, int height, PerfectFlowConfig.MotionBlur settings, ByteBuffer output) {
        ensureAccumulationResources(width, height);
        copyAttachmentToTexture(sourceAttachment, accumulationSourceTexture, width, height);

        if (!accumulationInitialized) {
            renderToTexture(new int[]{accumulationSourceTexture}, new float[]{1.0F}, 1, accumulationTextures[0], width, height);
            readTextureToBuffer(accumulationTextures[0], output, width, height);
            accumulationActiveIndex = 0;
            accumulationInitialized = true;
            return true;
        }

        int previousIndex = accumulationActiveIndex;
        int nextIndex = 1 - previousIndex;
        float alpha = clamp((float) (settings.shutterFraction / Math.max(1, settings.sampleCount)), MIN_ACCUMULATION_ALPHA, 1.0F);
        renderToTexture(
                new int[]{accumulationSourceTexture, accumulationTextures[previousIndex]},
                new float[]{alpha, 1.0F - alpha},
                2,
                accumulationTextures[nextIndex],
                width,
                height
        );
        readTextureToBuffer(accumulationTextures[nextIndex], output, width, height);
        accumulationActiveIndex = nextIndex;
        return true;
    }

    private void ensureBaseResources(int width, int height) {
        if (width == this.width && height == this.height && program != 0 && outputFramebuffer != 0 && vao != 0 && vbo != 0) {
            return;
        }

        releaseResources();
        this.width = width;
        this.height = height;

        program = createProgram();
        outputFramebuffer = GL30.glGenFramebuffers();

        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexData = BufferUtils.createFloatBuffer(6);
        vertexData.put(-1.0F).put(-1.0F);
        vertexData.put(3.0F).put(-1.0F);
        vertexData.put(-1.0F).put(3.0F);
        vertexData.flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexData, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 2 * Float.BYTES, 0L);
        GL20.glEnableVertexAttribArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);

        samplerUniformLocation = GL20.glGetUniformLocation(program, "u_textures[0]");
        weightsUniformLocation = GL20.glGetUniformLocation(program, "u_weights[0]");
        historySizeUniformLocation = GL20.glGetUniformLocation(program, "u_historySize");
        GL20.glUseProgram(program);
        samplerUnitsBuffer.position(0);
        GL20.glUniform1iv(samplerUniformLocation, samplerUnitsBuffer);
        GL20.glUseProgram(0);

        frameBlendOutputTexture = createTexture();
        allocateTexture(frameBlendOutputTexture, width, height);
        accumulationSourceTexture = createTexture();
        allocateTexture(accumulationSourceTexture, width, height);
        accumulationTextures = new int[]{createTexture(), createTexture()};
        allocateTexture(accumulationTextures[0], width, height);
        allocateTexture(accumulationTextures[1], width, height);
    }

    private void ensureFrameBlendResources(int width, int height, int historyCount) {
        if (frameBlendTextures.length != historyCount) {
            releaseTextures(frameBlendTextures);
            frameBlendTextures = new int[historyCount];
            for (int i = 0; i < historyCount; i++) {
                frameBlendTextures[i] = createTexture();
                allocateTexture(frameBlendTextures[i], width, height);
            }
            frameBlendWriteIndex = 0;
            frameBlendCount = 0;
        }
        if (frameBlendOutputTexture == 0) {
            frameBlendOutputTexture = createTexture();
            allocateTexture(frameBlendOutputTexture, width, height);
        }
    }

    private void ensureAccumulationResources(int width, int height) {
        if (accumulationSourceTexture == 0) {
            accumulationSourceTexture = createTexture();
            allocateTexture(accumulationSourceTexture, width, height);
        }
        if (accumulationTextures.length != 2 || accumulationTextures[0] == 0 || accumulationTextures[1] == 0) {
            releaseTextures(accumulationTextures);
            accumulationTextures = new int[]{createTexture(), createTexture()};
            allocateTexture(accumulationTextures[0], width, height);
            allocateTexture(accumulationTextures[1], width, height);
        }
        accumulationInitialized = accumulationInitialized && this.width == width && this.height == height;
    }

    private void copyAttachmentToTexture(CaptureAttachment attachment, int textureId, int width, int height) {
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        attachment.bindRead();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTextureBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTextureBinding);
        GL13.glActiveTexture(previousActiveTexture);
        attachment.unbindRead();
    }

    private void renderToTexture(int[] textures, float[] weights, int historySize, int targetTexture, int width, int height) {
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);

        viewportBuffer.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewportBuffer);
        viewportBuffer.rewind();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFramebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, targetTexture, 0);
        GL30.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL30.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glViewport(0, 0, width, height);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        GL20.glUseProgram(program);
        GL20.glUniform1i(historySizeUniformLocation, historySize);
        weightsBuffer.clear();
        for (int i = 0; i < historySize; i++) {
            weightsBuffer.put(weights[i]);
        }
        weightsBuffer.flip();
        GL20.glUniform1fv(weightsUniformLocation, weightsBuffer);
        GL30.glBindVertexArray(vao);
        for (int i = 0; i < historySize; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            previousTextureBindings[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
        }
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        for (int i = historySize - 1; i >= 0; i--) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTextureBindings[i]);
        }
        GL30.glBindVertexArray(previousVao);
        GL20.glUseProgram(previousProgram);

        if (cull) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
        if (blend) {
            GL11.glEnable(GL11.GL_BLEND);
        } else {
            GL11.glDisable(GL11.GL_BLEND);
        }
        if (depthTest) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        } else {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        if (scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GL13.glActiveTexture(previousActiveTexture);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        GL11.glViewport(viewportBuffer.get(0), viewportBuffer.get(1), viewportBuffer.get(2), viewportBuffer.get(3));
    }

    private void readTextureToBuffer(int textureId, ByteBuffer output, int width, int height) {
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFramebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, textureId, 0);
        GL30.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL12.GL_BGR, GL11.GL_UNSIGNED_BYTE, output);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        GL11.glReadBuffer(previousReadBuffer);
    }

    private int createTexture() {
        int texture = GL11.glGenTextures();
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(previousActiveTexture);
        return texture;
    }

    private void allocateTexture(int textureId, int width, int height) {
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, width, height, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(previousActiveTexture);
    }

    private void releaseResources() {
        releaseTextures(frameBlendTextures);
        releaseTextures(accumulationTextures);
        if (frameBlendOutputTexture != 0) {
            GL11.glDeleteTextures(frameBlendOutputTexture);
            frameBlendOutputTexture = 0;
        }
        if (accumulationSourceTexture != 0) {
            GL11.glDeleteTextures(accumulationSourceTexture);
            accumulationSourceTexture = 0;
        }
        if (outputFramebuffer != 0) {
            GL30.glDeleteFramebuffers(outputFramebuffer);
            outputFramebuffer = 0;
        }
        if (vbo != 0) {
            GL15.glDeleteBuffers(vbo);
            vbo = 0;
        }
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
        frameBlendTextures = new int[0];
        accumulationTextures = new int[0];
        reset();
    }

    private void releaseTextures(int[] textures) {
        if (textures == null) {
            return;
        }
        for (int texture : textures) {
            if (texture != 0) {
                GL11.glDeleteTextures(texture);
            }
        }
    }

    private int createProgram() {
        int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, readResource("/assets/perfectflow/shaders/motion_blur.vert"));
        int fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, readResource("/assets/perfectflow/shaders/motion_blur.frag"));
        int createdProgram = GL20.glCreateProgram();
        GL20.glAttachShader(createdProgram, vertexShader);
        GL20.glAttachShader(createdProgram, fragmentShader);
        GL20.glLinkProgram(createdProgram);
        if (GL20.glGetProgrami(createdProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(createdProgram);
            GL20.glDeleteShader(vertexShader);
            GL20.glDeleteShader(fragmentShader);
            GL20.glDeleteProgram(createdProgram);
            throw new IllegalStateException("GPU motion blur program link failed: " + log);
        }
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
        return createdProgram;
    }

    private int compileShader(int shaderType, String source) {
        int shader = GL20.glCreateShader(shaderType);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("GPU motion blur shader compile failed: " + log);
        }
        return shader;
    }

    private String readResource(String path) {
        try (InputStream inputStream = Objects.requireNonNull(GpuMotionBlurPipeline.class.getResourceAsStream(path), "Missing resource: " + path)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read shader resource " + path, exception);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
