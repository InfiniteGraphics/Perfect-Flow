package com.perfectflow.capture.export;

import com.perfectflow.capture.CaptureSession;
import com.perfectflow.capture.frame.CapturedFrame;
import com.perfectflow.capture.frame.PixelFormat;

public interface FrameExporter extends AutoCloseable {
    void open(CaptureSession session, String streamName, int width, int height, PixelFormat format) throws Exception;

    void export(CapturedFrame frame) throws Exception;

    @Override
    void close() throws Exception;
}
