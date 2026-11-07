package net.kdt.pojavlaunch.utils;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * H.264 MP4 encoder for packed I420 frames.
 *
 * This encoder deliberately uses MediaCodec's flexible YUV input Image rather
 * than assuming a device-specific planar or semiplanar byte layout. The caller
 * supplies I420 (Y, U, V) frames and monotonically increasing timestamps.
 */
public final class I420VideoEncoder implements AutoCloseable {
    private static final String MIME = "video/avc";
    private static final String TAG = "ElytraI420Encoder";
    private static final long CODEC_TIMEOUT_US = 10_000L;
    private static final long STOP_TIMEOUT_MS = 10_000L;

    private MediaCodec codec;
    private MediaMuxer muxer;
    private Thread drainThread;
    private volatile boolean started;
    private volatile boolean stopping;
    private volatile IOException drainFailure;
    private boolean muxerStarted;
    private int trackIndex = -1;
    private int width;
    private int height;
    private long lastPresentationTimeUs = -1L;

    public synchronized void start(File output, int width, int height,
                                   int bitRate, int frameRate) throws IOException {
        if (started || codec != null) throw new IllegalStateException("Encoder already started");
        if (output == null || width < 2 || height < 2 || (width & 1) != 0
                || (height & 1) != 0 || bitRate < 1 || frameRate < 1) {
            throw new IllegalArgumentException("Invalid output, dimensions, or quality");
        }

        File parent = output.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create recording directory: " + parent);
        }

        MediaCodecInfo encoderInfo = findFlexibleYuvEncoder();
        if (encoderInfo == null) {
            throw new IOException("No AVC encoder supports flexible YUV input");
        }
        String acceleration = Build.VERSION.SDK_INT >= 29
                ? Boolean.toString(encoderInfo.isHardwareAccelerated()) : "unknown";
        Log.i(TAG, "Selected AVC encoder: " + encoderInfo.getName()
                + " (hardware accelerated: " + acceleration + ")");

        this.width = width;
        this.height = height;
        this.lastPresentationTimeUs = -1L;
        this.drainFailure = null;
        this.muxerStarted = false;
        this.trackIndex = -1;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            codec = MediaCodec.createByCodecName(encoderInfo.getName());
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            codec.start();
            started = true;
            stopping = false;
            drainThread = new Thread(this::drainLoop, "ElytraI420Encoder");
            drainThread.start();
        } catch (IOException | RuntimeException e) {
            releaseResources();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Could not initialize I420 H.264 encoder", e);
        }
    }

    private static MediaCodecInfo findFlexibleYuvEncoder() {
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo fallback = null;
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (!MIME.equalsIgnoreCase(type)) continue;
                try {
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    boolean supportsFlexibleYuv = false;
                    for (int color : caps.colorFormats) {
                        if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                            supportsFlexibleYuv = true;
                            break;
                        }
                    }
                    if (!supportsFlexibleYuv) continue;

                    // Prefer the device's hardware AVC encoder. Selecting the first
                    // advertised codec can accidentally choose a slow software encoder.
                    if (Build.VERSION.SDK_INT >= 29 && info.isHardwareAccelerated()) {
                        return info;
                    }
                    if (fallback == null) fallback = info;
                } catch (RuntimeException ignored) {
                    // Skip codecs whose capability query is broken on this device.
                }
            }
        }
        return fallback;
    }

    /**
     * Queues one packed I420 frame. Returns false if the codec has no input
     * slot available immediately; callers should drop that frame, not stall
     * Minecraft's render thread.
     *
     * @param i420 packed Y, U, V planes
     * @param presentationTimeUs timestamp in microseconds
     */
    public synchronized boolean encodeFrame(ByteBuffer i420, long presentationTimeUs)
            throws IOException {
        if (!started || stopping || codec == null) {
            throw new IllegalStateException("Encoder is not accepting frames");
        }
        if (i420 == null) throw new IllegalArgumentException("I420 frame is required");

        long ySizeLong = (long) width * height;
        long frameSizeLong = ySizeLong + ySizeLong / 2L;
        if (frameSizeLong > Integer.MAX_VALUE || i420.remaining() < frameSizeLong) {
            throw new IllegalArgumentException("I420 frame buffer is too small");
        }

        int inputIndex = codec.dequeueInputBuffer(0);
        if (inputIndex < 0) return false;

        Image image = null;
        try {
            image = codec.getInputImage(inputIndex);
            if (image == null) {
                codec.queueInputBuffer(inputIndex, 0, 0,
                        Math.max(0L, presentationTimeUs), 0);
                return false;
            }
            copyI420ToImage(i420, image, width, height);
            long pts = Math.max(presentationTimeUs, lastPresentationTimeUs + 1L);
            codec.queueInputBuffer(inputIndex, 0, (int) frameSizeLong, pts, 0);
            lastPresentationTimeUs = pts;
            return true;
        } catch (RuntimeException e) {
            try {
                codec.queueInputBuffer(inputIndex, 0, 0, Math.max(0L, presentationTimeUs), 0);
            } catch (RuntimeException ignored) {}
            throw new IOException("Could not submit I420 frame to AVC encoder", e);
        }
    }

    private static void copyI420ToImage(ByteBuffer source, Image image,
                                        int width, int height) throws IOException {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) {
            throw new IOException("AVC encoder returned an invalid YUV input image");
        }

        int sourceBase = source.position();
        int ySize = width * height;
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        copyPlane(source, sourceBase, width, height, planes[0]);
        copyPlane(source, sourceBase + ySize, chromaWidth, chromaHeight, planes[1]);
        copyPlane(source, sourceBase + ySize + ySize / 4,
                chromaWidth, chromaHeight, planes[2]);
    }

    private static void copyPlane(ByteBuffer source, int sourceOffset,
                                  int planeWidth, int planeHeight, Image.Plane plane)
            throws IOException {
        ByteBuffer destination = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        if (destination == null || rowStride <= 0 || pixelStride <= 0) {
            throw new IOException("AVC encoder returned invalid YUV plane strides");
        }

        long lastByte = (long) (planeHeight - 1) * rowStride
                + (long) (planeWidth - 1) * pixelStride;
        int destinationBase = destination.position();
        if (lastByte >= (long) destination.limit() - destinationBase) {
            throw new IOException("AVC encoder YUV plane buffer is too small");
        }

        for (int row = 0; row < planeHeight; row++) {
            int srcRow = sourceOffset + row * planeWidth;
            int dstRow = destinationBase + row * rowStride;
            for (int col = 0; col < planeWidth; col++) {
                destination.put(dstRow + col * pixelStride, source.get(srcRow + col));
            }
        }
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                MediaCodec local = codec;
                if (local == null) break;
                int index = local.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IOException("Encoder output format changed twice");
                    trackIndex = muxer.addTrack(local.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                    continue;
                }
                if (index < 0) continue;

                ByteBuffer data = local.getOutputBuffer(index);
                if (data != null && info.size > 0 && muxerStarted
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    data.position(info.offset);
                    data.limit(info.offset + info.size);
                    muxer.writeSampleData(trackIndex, data, info);
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                local.releaseOutputBuffer(index, false);
                if (eos) break;
            }
        } catch (IOException | RuntimeException e) {
            drainFailure = e instanceof IOException ? (IOException) e
                    : new IOException("Failed while writing encoded MP4 samples", e);
        }
    }

    public void stop() throws IOException {
        Thread thread;
        synchronized (this) {
            if (!started && codec == null) return;
            stopping = true;
            thread = drainThread;
            if (codec != null) {
                try {
                    int inputIndex = -1;
                    long deadline = System.currentTimeMillis() + 5_000L;
                    while (inputIndex < 0 && System.currentTimeMillis() < deadline) {
                        inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    }
                    if (inputIndex >= 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0,
                                Math.max(0L, lastPresentationTimeUs + 1L),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        drainFailure = new IOException("Timed out waiting to finalize MP4 input");
                    }
                } catch (RuntimeException e) {
                    drainFailure = new IOException("Could not signal encoder end-of-stream", e);
                }
            }
        }

        try {
            if (thread != null) {
                thread.join(STOP_TIMEOUT_MS);
                if (thread.isAlive()) {
                    thread.interrupt();
                    drainFailure = new IOException("Timed out finalizing MP4 recording");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            drainFailure = new IOException("Interrupted while finalizing MP4 recording", e);
        } finally {
            synchronized (this) {
                releaseResources();
            }
        }
        if (drainFailure != null) throw drainFailure;
    }

    private void releaseResources() {
        started = false;
        stopping = true;
        drainThread = null;
        if (codec != null) {
            try { codec.stop(); } catch (RuntimeException ignored) {}
            try { codec.release(); } catch (RuntimeException ignored) {}
            codec = null;
        }
        if (muxer != null) {
            try { if (muxerStarted) muxer.stop(); } catch (RuntimeException ignored) {}
            try { muxer.release(); } catch (RuntimeException ignored) {}
            muxer = null;
        }
        muxerStarted = false;
        trackIndex = -1;
    }

    @Override
    public void close() throws IOException {
        stop();
    }
}
