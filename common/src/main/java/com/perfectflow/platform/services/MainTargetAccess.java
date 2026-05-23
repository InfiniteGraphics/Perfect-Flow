package com.perfectflow.platform.services;

public interface MainTargetAccess {
    int width();

    int height();

    void bindReadForColor();

    void bindReadForDepth();

    void unbindRead();
}
