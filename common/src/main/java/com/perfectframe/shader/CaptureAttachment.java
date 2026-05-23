package com.perfectframe.shader;

public interface CaptureAttachment {
    int width();

    int height();

    void bindRead();

    void unbindRead();

    default void destroy() {
    }
}
