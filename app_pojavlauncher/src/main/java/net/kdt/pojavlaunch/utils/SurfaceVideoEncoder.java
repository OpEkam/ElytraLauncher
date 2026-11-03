package net.kdt.pojavlaunch.utils;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * H.264 MP4 encoder backed by an input Surface.
 *
 * The renderer must draw/copy game frames into {@link #getInputSurface()}.
 * This class deliberately does not change the game's EGL context or render
 * loop; callers own frame transfer and must stop this encoder before releasing
 * their EGL resources.
 */
public final class SurfaceVideoEncoder implements AutoCloseable {
    private static final String MIME_TYPE = "video/avc";
    private static final int IFRAME_INTERVAL_SECONDS = 2;
    private static final long CODEC_TIMEOUT_US = 10_000L;

    private MediaCodec codec;
    private MediaMuxer muxer;
    private Surface inputSurface;
    private Thread drainThread;
    private volatile boolean stopping;
    private volatile boolean started;
    private volatile IOException drainFailure;
    private int trackIndex = -1;
    private boolean muxerStarted;

    public synchronized void start(File output, int width, int height, int bitRate, int frameRate)
            throws IOException {
        if (started || codec != null) throw new IllegalStateException("Encoder already started");
        if (output == null || width < 2 || height < 2 || bitRate < 1 || frameRate < 1) {
            throw new IllegalArgumentException("Invalid output or video dimensions/quality");
        }
        if ((width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("H.264 dimensions must be even");
        }

        File parent = output.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create recording directory: " + parent);
        }

        drainFailure = null;
        muxerStarted = false;
        trackIndex = -1;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_SECONDS);

            codec = MediaCodec.createEncoderByType(MIME_TYPE);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = codec.createInputSurface();
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            codec.start();
            started = true;
            stopping = false;
            drainThread = new Thread(this::drainLoop, "ElytraVideoEncoder");
            drainThread.start();
        } catch (IOException | RuntimeException e) {
            releaseResources();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Could not initialize H.264 MP4 encoder", e);
        }
    }

    /** Surface to be used by a compatible EGL context for frame rendering/copying. */
    public synchronized Surface getInputSurface() {
        if (!started || inputSurface == null) throw new IllegalStateException("Encoder is not running");
        return inputSurface;
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                MediaCodec localCodec = codec;
                if (localCodec == null) break;
                int index = localCodec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // After signalEndOfInputStream(), keep draining until the codec
                    // actually emits BUFFER_FLAG_END_OF_STREAM. Exiting here can
                    // truncate the MP4 before its final samples are written.
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IOException("Encoder format changed more than once");
                    trackIndex = muxer.addTrack(localCodec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                    continue;
                }
                if (index < 0) continue;

                ByteBuffer data = localCodec.getOutputBuffer(index);
                if (data != null && info.size > 0 && muxerStarted
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    data.position(info.offset);
                    data.limit(info.offset + info.size);
                    muxer.writeSampleData(trackIndex, data, info);
                }
                boolean endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                localCodec.releaseOutputBuffer(index, false);
                if (endOfStream) break;
            }
        } catch (IOException | RuntimeException e) {
            drainFailure = e instanceof IOException ? (IOException) e
                    : new IOException("MP4 encoder failed while writing frames", e);
        }
    }

    /**
     * Stops the encoder and finalizes the MP4. The caller must stop submitting
     * frames before calling this method.
     */
    public void stop() throws IOException {
        Thread thread;
        synchronized (this) {
            if (!started && codec == null) return;
            stopping = true;
            thread = drainThread;
        }

        try {
            if (codec != null) {
                try {
                    codec.signalEndOfInputStream();
                } catch (RuntimeException e) {
                    drainFailure = new IOException("Could not finalize encoder input", e);
                }
            }
            if (thread != null) {
                try {
                    thread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    drainFailure = new IOException("Interrupted while stopping video encoder", e);
                }
                if (thread.isAlive()) {
                    thread.interrupt();
                    drainFailure = new IOException("Timed out finalizing MP4 recording");
                }
            }
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
        if (inputSurface != null) {
            try { inputSurface.release(); } catch (RuntimeException ignored) {}
            inputSurface = null;
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
