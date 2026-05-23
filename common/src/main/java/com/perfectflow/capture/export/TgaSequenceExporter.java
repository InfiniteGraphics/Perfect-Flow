package com.perfectflow.capture.export;

import com.perfectflow.capture.CaptureSession;
import com.perfectflow.capture.frame.CapturedFrame;
import com.perfectflow.capture.frame.PixelFormat;

import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

public final class TgaSequenceExporter implements FrameExporter {
    private static final long ENQUEUE_POLL_MILLIS = 100L;
    private static final QueuedFrame POISON = new QueuedFrame(null, true);

    private CaptureSession session;
    private Path streamDirectory;
    private int width;
    private int height;
    private PixelFormat format;
    private MotionBlurFrameProcessor motionBlurProcessor;
    private BlockingQueue<QueuedFrame> queue;
    private int queueCapacity;
    private long stallTimeoutMillis;
    private Thread writerThread;
    private volatile Exception writerFailure;
    private volatile boolean closing;

    @Override
    public void open(CaptureSession session, String streamName, int width, int height, PixelFormat format) throws Exception {
        this.session = session;
        this.width = width;
        this.height = height;
        this.format = format;
        this.streamDirectory = session.outputDirectory().resolve(session.name()).resolve(streamName);
        Files.createDirectories(streamDirectory);

        motionBlurProcessor = new MotionBlurFrameProcessor(session.config(), streamName);
        queueCapacity = session.config().ffmpeg.writerQueueCapacityFrames;
        stallTimeoutMillis = session.config().ffmpeg.writerStallTimeoutMillis;
        queue = new ArrayBlockingQueue<>(queueCapacity);
        session.setExporterQueueStatus(0, queueCapacity);
        writerThread = new Thread(this::writeQueuedFrames, "PerfectFlow TGA Writer " + streamName);
        writerThread.setDaemon(true);
        writerThread.start();
    }

    @Override
    public void export(CapturedFrame frame) throws Exception {
        throwIfWriterFailed();
        if (closing) {
            throw new IllegalStateException("TGA writer is already closing");
        }
        enqueueFrame(frame);
    }

    @Override
    public void close() throws Exception {
        closing = true;
        if (queue != null) {
            while (true) {
                if (queue.offer(POISON, 100, TimeUnit.MILLISECONDS)) {
                    break;
                }
                throwIfWriterFailed();
                if (writerThread != null && !writerThread.isAlive()) {
                    break;
                }
            }
        }
        if (writerThread != null) {
            writerThread.join(TimeUnit.SECONDS.toMillis(10));
            if (writerThread.isAlive()) {
                writerThread.interrupt();
                throw new IllegalStateException("TGA writer did not stop cleanly");
            }
        }
        throwIfWriterFailed();
    }

    private void enqueueFrame(CapturedFrame frame) throws Exception {
        QueuedFrame queuedFrame = new QueuedFrame(frame, false);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stallTimeoutMillis);
        while (!queue.offer(queuedFrame, ENQUEUE_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            throwIfWriterFailed();
            session.setExporterQueueStatus(queue.size(), queueCapacity);
            if (writerThread != null && !writerThread.isAlive()) {
                throw new IllegalStateException("TGA writer stopped while frames were still queued");
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("TGA writer could not keep up for " + stallTimeoutMillis + "ms.");
            }
        }
        session.setExporterQueueStatus(queue.size(), queueCapacity);
    }

    private void writeQueuedFrames() {
        try {
            while (true) {
                QueuedFrame queuedFrame = queue.take();
                if (queuedFrame.poison()) {
                    break;
                }
                try {
                    writeFrame(queuedFrame.frame(), motionBlurProcessor == null ? queuedFrame.frame().pixels() : motionBlurProcessor.process(queuedFrame.frame()));
                    session.setExporterQueueStatus(queue.size(), queueCapacity);
                } finally {
                    queuedFrame.frame().release();
                }
            }
        } catch (Exception exception) {
            writerFailure = exception;
        }
    }

    private void writeFrame(CapturedFrame frame, ByteBuffer pixels) throws Exception {
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
            channel.write(pixels);
        }
    }

    private void throwIfWriterFailed() throws Exception {
        if (writerFailure != null) {
            throw writerFailure;
        }
    }

    private record QueuedFrame(CapturedFrame frame, boolean poison) {
    }
}
