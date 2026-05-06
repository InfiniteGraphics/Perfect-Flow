package com.perfectframe.capture.export;

import com.perfectframe.capture.CaptureSession;
import com.perfectframe.capture.frame.CapturedFrame;
import com.perfectframe.capture.frame.PixelFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

public final class TgaSequenceExporter implements FrameExporter {
    private Path streamDirectory;
    private int width;
    private int height;
    private PixelFormat format;

    @Override
    public void open(CaptureSession session, String streamName, int width, int height, PixelFormat format) throws Exception {
        this.width = width;
        this.height = height;
        this.format = format;
        this.streamDirectory = session.outputDirectory().resolve(session.name()).resolve(streamName);
        Files.createDirectories(streamDirectory);
    }

    @Override
    public void export(CapturedFrame frame) throws Exception {
        Path target = streamDirectory.resolve(String.format("%06d.tga", frame.frameIndex()));
        ByteBuffer header = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
        header.position(2);
        header.put((byte) 2);
        header.position(12);
        header.putShort((short) (width & 0xffff));
        header.putShort((short) (height & 0xffff));
        header.position(16);
        header.put((byte) format.tgaBitsPerPixel());
        header.position(17);
        header.put((byte) 0);
        header.rewind();

        try (FileChannel channel = FileChannel.open(target, CREATE, WRITE, TRUNCATE_EXISTING)) {
            channel.write(header);
            channel.write(frame.pixels());
        }
    }

    @Override
    public void close() {
    }
}
