package net.kdt.pojavlaunch.utils;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * H.264 MP4 encoder that accepts tightly packed planar I420 frames.
 *
 * This encoder intentionally selects only codecs advertising
 * COLOR_FormatYUV420Planar. It does not assume that a device's preferred
 * encoder accepts arbitrary YUV layouts.
 */
public final class ByteBufferVideoEncoder implements AutoCloseable {
    private static final String TAG = "ElytraRecorder";
    private static final String MIME_TYPE = "video/avc";
    private static final long CODEC_TIMEOUT_US = 10_000L;
    private static final long STOP_TIMEOUT_MS = 5_000L;

    private MediaCodec codec;
    private MediaMuxer muxer;
    private boolean started;
    private boolean muxerStarted;
    private boolean inputEosQueued;
    private int trackIndex = -1;
    private long lastPresentationTimeUs = -1L;
    private IOException failure;

    /**
     * Starts encoding. Dimensions must be even and the output is an MP4 file.
     */
    public synchronized void start(File output, int width, int height,
                                   int bitRate, int frameRate) throws IOException {
        if (started || codec != null || muxer != null) {
            throw new IllegalStateException("Encoder is already initialized");
        }
        if (output == null || width < 2 || height < 2 || (width & 1) != 0
                || (height & 1) != 0 || bitRate < 1 || frameRate < 1) {
            throw new IllegalArgumentException("Invalid output, dimensions, or quality");
        }

        long frameBytesLong = (long) width * (long) height * 3L / 2L;
        if (frameBytesLong > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Frame is too large");
        }

        File parent = output.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()
                && !parent.isDirectory()) {
            throw new IOException("Cannot create recording directory: " + parent);
        }

        MediaCodecInfo selected = findPlanarAvcEncoder();
        if (selected == null) {
            throw new IOException("No H.264 encoder advertises planar YUV420 input");
        }

        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (int) frameBytesLong);

            codec = MediaCodec.createByCodecName(selected.getName());
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            codec.start();
            started = true;
            muxerStarted = false;
            inputEosQueued = false;
            trackIndex = -1;
            lastPresentationTimeUs = -1L;
            failure = null;
        } catch (IOException | RuntimeException e) {
            releaseResources();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Could not initialize planar H.264 encoder", e);
        }
    }

    private static MediaCodecInfo findPlanarAvcEncoder() {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps =
                        info.getCapabilitiesForType(MIME_TYPE);
                for (int color : caps.colorFormats) {
                    if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                        return info;
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // This codec does not advertise AVC.
            }
        }
        return null;
    }

    /**
     * Queues one tightly packed I420 frame. Returns false if the codec has no
     * input slot before the short timeout; callers may drop that frame.
     */
    public synchronized boolean encodeFrame(ByteBuffer i420, long presentationTimeUs)
            throws IOException {
        ensureRunning();
        if (i420 == null) throw new IllegalArgumentException("I420 frame is required");
        if (presentationTimeUs < 0 || presentationTimeUs <= lastPresentationTimeUs) {
            throw new IllegalArgumentException("Presentation timestamps must increase");
        }

        int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
        if (inputIndex < 0) {
            drainOutput(false);
            return false;
        }

        ByteBuffer input = codec.getInputBuffer(inputIndex);
        if (input == null) {
            codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0);
            throw new IOException("Encoder returned a null input buffer");
        }
        input.clear();
        ByteBuffer frame = i420.duplicate();
        if (frame.remaining() > input.remaining()) {
            codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0);
            throw new IOException("I420 frame exceeds encoder input capacity");
        }
        int size = frame.remaining();
        input.put(frame);
        codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0);
        lastPresentationTimeUs = presentationTimeUs;
        drainOutput(false);
        return true;
    }

    private void drainOutput(boolean waitForEos) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = System.currentTimeMillis() + (waitForEos ? STOP_TIMEOUT_MS : 0L);
        boolean eos = false;
        do {
            int outputIndex = codec.dequeueOutputBuffer(info, waitForEos ? CODEC_TIMEOUT_US : 0L);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!waitForEos || System.currentTimeMillis() >= deadline) break;
                continue;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw new IOException("Encoder output format changed twice");
                trackIndex = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            }
            if (outputIndex < 0) continue;

            ByteBuffer encoded = codec.getOutputBuffer(outputIndex);
            if (encoded != null && info.size > 0 && muxerStarted
                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                encoded.position(info.offset);
                encoded.limit(info.offset + info.size);
                muxer.writeSampleData(trackIndex, encoded, info);
            }
            eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(outputIndex, false);
        } while (eos == false && waitForEos && System.currentTimeMillis() < deadline);

        if (waitForEos && !eos) {
            throw new IOException("Timed out waiting for encoder end-of-stream");
        }
    }

    private void ensureRunning() throws IOException {
        if (!started || codec == null || inputEosQueued) {
            throw new IllegalStateException("Encoder is not accepting frames");
        }
        if (failure != null) throw failure;
    }

    /** Signals end-of-stream, drains pending samples, and finalizes the MP4. */
    public synchronized void stop() throws IOException {
        if (!started && codec == null && muxer == null) return;
        IOException stopFailure = null;
        try {
            if (codec != null && started && !inputEosQueued) {
                int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                if (inputIndex < 0) throw new IOException("No input slot available to finalize encoder");
                long pts = Math.max(0L, lastPresentationTimeUs + 1L);
                codec.queueInputBuffer(inputIndex, 0, 0, pts,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                inputEosQueued = true;
                drainOutput(true);
            }
        } catch (IOException | RuntimeException e) {
            stopFailure = e instanceof IOException ? (IOException) e
                    : new IOException("Could not finalize MP4 recording", e);
        } finally {
            releaseResources();
        }
        if (stopFailure != null) throw stopFailure;
    }

    private void releaseResources() {
        started = false;
        inputEosQueued = true;
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
