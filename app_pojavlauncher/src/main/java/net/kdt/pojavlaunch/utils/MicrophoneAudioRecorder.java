package net.kdt.pojavlaunch.utils;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Microphone-to-AAC/MP4 capture helper.
 *
 * This is an audio-track building block for gameplay recording. It intentionally
 * writes a temporary audio-only MP4; the gameplay recorder will mux this track
 * with its video track in a later integration step.
 */
public final class MicrophoneAudioRecorder implements AutoCloseable {
    private static final String MIME = "audio/mp4a-latm";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_COUNT = 1;
    private static final int BIT_RATE = 96000;
    private static final long CODEC_TIMEOUT_US = 10000L;

    private final Object lock = new Object();
    private AudioRecord audioRecord;
    private MediaCodec codec;
    private MediaMuxer muxer;
    private Thread worker;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean stopRequested;
    private volatile IOException failure;
    private boolean muxerStarted;
    private int audioTrack = -1;
    private long submittedSamples;
    private long pausedSamples;
    private long pauseStartedNs;

    /** Starts microphone capture on a worker thread. Requires RECORD_AUDIO permission. */
    public void start(File output) throws IOException {
        synchronized (lock) {
            if (running || worker != null) throw new IllegalStateException("Microphone recorder is active");
            if (output == null) throw new IllegalArgumentException("Output file is required");
            File parent = output.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Cannot create audio recording directory");
            }

            int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) throw new IOException("Microphone audio format is unavailable");
            int bufferSize = Math.max(minBuffer * 2, 8192);
            try {
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IOException("Could not initialize microphone input");
                }

                MediaFormat format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNEL_COUNT);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize);

                codec = MediaCodec.createEncoderByType(MIME);
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                muxer = new MediaMuxer(output.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                codec.start();
                audioRecord.startRecording();
                running = true;
                paused = false;
                stopRequested = false;
                failure = null;
                submittedSamples = 0L;
                pausedSamples = 0L;
                pauseStartedNs = 0L;
                worker = new Thread(this::recordLoop, "ElytraMicrophoneRecorder");
                worker.start();
            } catch (IOException | RuntimeException e) {
                releaseResources();
                if (e instanceof IOException) throw (IOException) e;
                throw new IOException("Could not start microphone AAC recording", e);
            }
        }
    }

    public boolean isRecording() { return running; }
    public boolean isPaused() { return paused; }

    public void pause() {
        synchronized (lock) {
            if (running && !paused) {
                paused = true;
                pauseStartedNs = System.nanoTime();
            }
        }
    }

    public void resume() {
        synchronized (lock) {
            if (running && paused) {
                if (pauseStartedNs != 0L) pausedSamples += System.nanoTime() - pauseStartedNs;
                pauseStartedNs = 0L;
                paused = false;
            }
        }
    }

    public void stop() throws IOException {
        Thread thread;
        synchronized (lock) {
            if (worker == null) {
                IOException error = failure;
                failure = null;
                if (error != null) throw error;
                return;
            }
            stopRequested = true;
            thread = worker;
        }
        try {
            thread.join(12000L);
            if (thread.isAlive()) {
                thread.interrupt();
                throw new IOException("Timed out stopping microphone recording");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping microphone recording", e);
        }
        synchronized (lock) {
            worker = null;
            running = false;
            paused = false;
            IOException error = failure;
            failure = null;
            if (error != null) throw error;
        }
    }

    private void recordLoop() {
        short[] samples = new short[2048];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!stopRequested) {
                if (paused) {
                    Thread.sleep(10L);
                    continue;
                }
                int count = audioRecord.read(samples, 0, samples.length);
                if (count < 0) throw new IOException("Microphone read failed: " + count);
                if (count == 0) continue;
                queuePcm(samples, count);
                drain(false, info);
            }
            queueEndOfStream(info);
            drain(true, info);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!stopRequested) failure = new IOException("Microphone recorder interrupted", e);
        } catch (IOException | RuntimeException e) {
            failure = e instanceof IOException ? (IOException) e
                    : new IOException("Microphone AAC encoding failed", e);
        } finally {
            releaseResources();
            synchronized (lock) {
                running = false;
                paused = false;
                worker = null;
            }
        }
    }

    private void queuePcm(short[] samples, int count) throws IOException {
        int input = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
        if (input < 0) return; // Drop this small audio block rather than stall capture.
        ByteBuffer buffer = codec.getInputBuffer(input);
        if (buffer == null) throw new IOException("AAC encoder returned no input buffer");
        buffer.clear();
        int bytes = count * 2;
        if (buffer.remaining() < bytes) throw new IOException("AAC input buffer is too small");
        for (int i = 0; i < count; i++) {
            short sample = samples[i];
            buffer.put((byte) sample);
            buffer.put((byte) (sample >>> 8));
        }
        long ptsUs = submittedSamples * 1000000L / SAMPLE_RATE;
        codec.queueInputBuffer(input, 0, bytes, ptsUs, 0);
        submittedSamples += count;
    }

    private void queueEndOfStream(MediaCodec.BufferInfo info) throws IOException {
        int input = -1;
        long deadline = System.currentTimeMillis() + 3000L;
        while (input < 0 && System.currentTimeMillis() < deadline) {
            input = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
        }
        if (input < 0) throw new IOException("Timed out finalizing microphone audio");
        long ptsUs = submittedSamples * 1000000L / SAMPLE_RATE;
        codec.queueInputBuffer(input, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
    }

    private void drain(boolean waitForEos, MediaCodec.BufferInfo info) throws IOException {
        long deadline = System.currentTimeMillis() + (waitForEos ? 5000L : 100L);
        while (true) {
            int index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!waitForEos || System.currentTimeMillis() >= deadline) return;
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw new IOException("AAC output format changed twice");
                audioTrack = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            }
            if (index < 0) continue;
            ByteBuffer data = codec.getOutputBuffer(index);
            if (data != null && info.size > 0 && muxerStarted
                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                data.position(info.offset);
                data.limit(info.offset + info.size);
                muxer.writeSampleData(audioTrack, data, info);
            }
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(index, false);
            if (eos) return;
        }
    }

    private void releaseResources() {
        if (audioRecord != null) {
            try { if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop(); }
            catch (RuntimeException ignored) {}
            try { audioRecord.release(); } catch (RuntimeException ignored) {}
            audioRecord = null;
        }
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
        audioTrack = -1;
    }

    @Override
    public void close() throws IOException { stop(); }
}
