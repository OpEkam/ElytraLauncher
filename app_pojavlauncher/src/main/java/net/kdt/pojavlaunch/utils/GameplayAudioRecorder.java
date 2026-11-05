package net.kdt.pojavlaunch.utils;

import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Captures eligible Android playback and optionally the microphone, mixes the
 * PCM samples, and encodes one AAC track for the final gameplay MP4.
 */
public final class GameplayAudioRecorder implements AutoCloseable {
    private static final String MIME = "audio/mp4a-latm";
    private static final int SAMPLE_RATE = 44100;
    private static final int BIT_RATE = 128000;
    private static final int BLOCK_SAMPLES = 1024;
    private static final long CODEC_TIMEOUT_US = 10000L;

    private final Object lock = new Object();
    private AudioRecord playbackRecord;
    private AudioRecord microphoneRecord;
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

    /**
     * A non-null projection enables Android playback capture (API 29+).
     * Microphone capture is optional and requires RECORD_AUDIO permission.
     */
    public void start(File output, MediaProjection projection, boolean includeMicrophone)
            throws IOException {
        synchronized (lock) {
            if (running || worker != null) throw new IllegalStateException("Audio recorder is active");
            if (output == null) throw new IllegalArgumentException("Output file is required");
            if (projection == null && !includeMicrophone) {
                throw new IllegalArgumentException("At least one audio source is required");
            }
            if (projection != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw new IOException("Android 10 or newer is required for game audio capture");
            }
            File parent = output.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Cannot create audio recording directory");
            }

            int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) throw new IOException("Audio input format is unavailable");
            int bufferSize = Math.max(minBuffer * 2, BLOCK_SAMPLES * 4);
            try {
                if (projection != null) {
                    AudioPlaybackCaptureConfiguration config =
                            new AudioPlaybackCaptureConfiguration.Builder(projection)
                                    .addMatchingUsage(AudioAttributesCompat.USAGE_GAME)
                                    .addMatchingUsage(AudioAttributesCompat.USAGE_MEDIA)
                                    .addMatchingUsage(AudioAttributesCompat.USAGE_UNKNOWN)
                                    .build();
                    AudioFormat playbackFormat = new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build();
                    playbackRecord = new AudioRecord.Builder()
                            .setAudioFormat(playbackFormat)
                            .setBufferSizeInBytes(bufferSize)
                            .setAudioPlaybackCaptureConfig(config)
                            .build();
                    if (playbackRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        throw new IOException("Could not initialize Android game-audio capture");
                    }
                }
                if (includeMicrophone) {
                    microphoneRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                    if (microphoneRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        throw new IOException("Could not initialize microphone input");
                    }
                }

                MediaFormat format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize * 2);
                codec = MediaCodec.createEncoderByType(MIME);
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                muxer = new MediaMuxer(output.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                codec.start();
                if (playbackRecord != null) playbackRecord.startRecording();
                if (microphoneRecord != null) microphoneRecord.startRecording();

                running = true;
                paused = false;
                stopRequested = false;
                failure = null;
                submittedSamples = 0L;
                worker = new Thread(this::recordLoop, "ElytraGameplayAudio");
                worker.start();
            } catch (IOException | RuntimeException e) {
                releaseResources();
                if (e instanceof IOException) throw (IOException) e;
                throw new IOException("Could not start gameplay audio capture", e);
            }
        }
    }

    public boolean isRecording() { return running; }
    public boolean isPaused() { return paused; }

    public void pause() {
        synchronized (lock) {
            if (running) paused = true;
        }
    }

    public void resume() {
        synchronized (lock) {
            if (running) paused = false;
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
                throw new IOException("Timed out stopping gameplay audio capture");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping gameplay audio", e);
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
        short[] playback = new short[BLOCK_SAMPLES];
        short[] microphone = new short[BLOCK_SAMPLES];
        short[] mixed = new short[BLOCK_SAMPLES];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!stopRequested) {
                if (paused) {
                    Thread.sleep(10L);
                    continue;
                }
                int playbackCount = playbackRecord == null ? -1
                        : playbackRecord.read(playback, 0, BLOCK_SAMPLES);
                int microphoneCount = microphoneRecord == null ? -1
                        : microphoneRecord.read(microphone, 0, BLOCK_SAMPLES);
                if (playbackRecord != null && playbackCount < 0) {
                    throw new IOException("Game-audio capture read failed: " + playbackCount);
                }
                if (microphoneRecord != null && microphoneCount < 0) {
                    throw new IOException("Microphone read failed: " + microphoneCount);
                }
                int count = playbackRecord == null ? microphoneCount
                        : microphoneRecord == null ? playbackCount
                        : Math.min(playbackCount, microphoneCount);
                if (count <= 0) continue;
                for (int i = 0; i < count; i++) {
                    int sample = (playbackRecord == null ? 0 : playback[i])
                            + (microphoneRecord == null ? 0 : microphone[i]);
                    mixed[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
                }
                queuePcm(mixed, count);
                drain(false, info);
            }
            queueEndOfStream();
            drain(true, info);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!stopRequested) failure = new IOException("Gameplay audio thread interrupted", e);
        } catch (IOException | RuntimeException e) {
            failure = e instanceof IOException ? (IOException) e
                    : new IOException("Gameplay audio encoding failed", e);
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
        if (input < 0) return;
        ByteBuffer buffer = codec.getInputBuffer(input);
        if (buffer == null) throw new IOException("AAC encoder returned no input buffer");
        buffer.clear();
        int bytes = count * 2;
        if (buffer.remaining() < bytes) throw new IOException("AAC input buffer is too small");
        for (int i = 0; i < count; i++) {
            buffer.put((byte) samples[i]);
            buffer.put((byte) (samples[i] >>> 8));
        }
        long ptsUs = submittedSamples * 1000000L / SAMPLE_RATE;
        codec.queueInputBuffer(input, 0, bytes, ptsUs, 0);
        submittedSamples += count;
    }

    private void queueEndOfStream() throws IOException {
        int input = -1;
        long deadline = System.currentTimeMillis() + 3000L;
        while (input < 0 && System.currentTimeMillis() < deadline) {
            input = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
        }
        if (input < 0) throw new IOException("Timed out finalizing gameplay audio");
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
        releaseRecord(playbackRecord);
        playbackRecord = null;
        releaseRecord(microphoneRecord);
        microphoneRecord = null;
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

    private static void releaseRecord(AudioRecord record) {
        if (record == null) return;
        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
        } catch (RuntimeException ignored) {}
        try { record.release(); } catch (RuntimeException ignored) {}
    }

    @Override
    public void close() throws IOException { stop(); }

    /** Android AudioAttributes usage constants without a second helper dependency. */
    private static final class AudioAttributesCompat {
        static final int USAGE_UNKNOWN = 0;
        static final int USAGE_MEDIA = 1;
        static final int USAGE_GAME = 14;
    }
}
