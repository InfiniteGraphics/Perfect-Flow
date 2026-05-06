package com.perfectframe.capture.export;

import com.perfectframe.capture.CaptureSession;
import com.perfectframe.capture.frame.CapturedFrame;
import com.perfectframe.capture.frame.PixelFormat;

public interface FrameExporter extends AutoCloseable {
    void open(CaptureSession session, String streamName, int width, int height, PixelFormat format) throws Exception;

    void export(CapturedFrame frame) throws Exception;

    @Override
    void close() throws Exception;
}
