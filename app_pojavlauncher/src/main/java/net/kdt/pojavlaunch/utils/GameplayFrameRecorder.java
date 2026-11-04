package net.kdt.pojavlaunch.utils;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Pulls OSMesa gameplay snapshots off the render thread, converts them to I420,
 * and writes an H.264 MP4. This currently supports the VK_ZINK/OSMesa capture
 * path only; unsupported renderers fail before recording begins.
 */
public final class GameplayFrameRecorder implements AutoCloseable {
    public interface Listener {
        void onRecordingError(IOException error);
    }

    private static final String TAG = "GameplayFrameRecorder";
    private static final long START_TIMEOUT_MS = 10000L;

    private final Object stateLock = new Object();
    private final Listener listener;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean stopRequested;
    private Thread worker;
    private File outputFile;
    private int bitRate;
    private int frameRate;
    private IOException workerFailure;
    private I420VideoEncoder encoder;

    public GameplayFrameRecorder(Listener listener) {
        this.listener = listener;
    }

    /** Starts asynchronously. The MP4 is created after the first valid gameplay frame. */
    public void start(File output, int requestedBitRate, int requestedFrameRate) throws IOException {
        if (output == null || requestedBitRate < 1 || requestedFrameRate < 1) {
            throw new IllegalArgumentException("Output file and valid encoding settings are required");
        }
        synchronized (stateLock) {
            if (running || worker != null) throw new IllegalStateException("Recorder is already active");
            outputFile = output;
            bitRate = requestedBitRate;
            frameRate = requestedFrameRate;
            workerFailure = null;
            stopRequested = false;
            paused = false;
            running = true;
            JREUtils.setFrameCaptureEnabled(true);
            worker = new Thread(this::captureLoop, "ElytraGameplayRecorder");
            worker.start();
        }
    }

    public void pause() {
        synchronized (stateLock) {
            if (running) paused = true;
        }
    }

    public void resume() {
        synchronized (stateLock) {
            if (running) paused = false;
        }
    }

    public boolean isRecording() {
        return running;
    }

    public boolean isPaused() {
        return paused;
    }

    /** Stops capture and finalizes the MP4. Safe to call from a non-render thread. */
    public void stop() throws IOException {
        Thread thread;
        synchronized (stateLock) {
            if (worker == null) {
                IOException failure = workerFailure;
                workerFailure = null;
                if (failure != null) throw failure;
                return;
            }
            stopRequested = true;
            thread = worker;
        }
        try {
            thread.join(15000L);
            if (thread.isAlive()) {
                thread.interrupt();
                throw new IOException("Timed out stopping gameplay recorder");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping gameplay recorder", e);
        } finally {
            JREUtils.setFrameCaptureEnabled(false);
        }
        synchronized (stateLock) {
            worker = null;
            running = false;
            paused = false;
            IOException failure = workerFailure;
            workerFailure = null;
            if (failure != null) throw failure;
        }
    }

    private void captureLoop() {
        ByteBuffer probe = ByteBuffer.allocateDirect(1);
        int[] info = new int[4];
        ByteBuffer rgba = null;
        ByteBuffer i420 = null;
        int frameWidth = 0, frameHeight = 0, rowStride = 0, lastSequence = -1;
        long startNs = 0L, pausedAtNs = 0L, pausedTotalNs = 0L;
        long frameIntervalNs = 1_000_000_000L / Math.max(1, frameRate);
        long lastSubmitNs = 0L;
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        try {
            // The first small-buffer call obtains frame metadata even though it cannot
            // copy pixels; allocate the exact native stride and retry.
            while (!stopRequested && System.currentTimeMillis() < deadline) {
                info[0] = info[1] = info[2] = info[3] = 0;
                JREUtils.copyLatestFrame(probe, info);
                if (info[0] > 1 && info[1] > 1 && info[2] >= info[0]) break;
                Thread.sleep(30L);
            }
            if (info[0] <= 1 || info[1] <= 1 || info[2] < info[0]) {
                throw new IOException("No OSMesa gameplay frames received; select the VK_ZINK renderer");
            }
            frameWidth = info[0] & ~1;
            frameHeight = info[1] & ~1;
            rowStride = info[2];
            long rgbaBytes = (long) rowStride * info[1] * 4L;
            long i420Bytes = (long) frameWidth * frameHeight * 3L / 2L;
            if (rgbaBytes > Integer.MAX_VALUE || i420Bytes > Integer.MAX_VALUE) {
                throw new IOException("Gameplay frame dimensions are too large");
            }
            rgba = ByteBuffer.allocateDirect((int) rgbaBytes);
            i420 = ByteBuffer.allocateDirect((int) i420Bytes);
            long copyDeadline = System.currentTimeMillis() + START_TIMEOUT_MS;
            boolean copied = false;
            while (!stopRequested && System.currentTimeMillis() < copyDeadline) {
                rgba.clear();
                if (JREUtils.copyLatestFrame(rgba, info)) {
                    copied = true;
                    break;
                }
                Thread.sleep(10L);
            }
            if (!copied) throw new IOException("Could not copy the initial gameplay frame");

            encoder = new I420VideoEncoder();
            encoder.start(outputFile, frameWidth, frameHeight, bitRate, frameRate);
            startNs = System.nanoTime();
            lastSequence = info[3];
            while (!stopRequested) {
                if (paused) {
                    if (pausedAtNs == 0L) pausedAtNs = System.nanoTime();
                    Thread.sleep(15L);
                    continue;
                }
                if (pausedAtNs != 0L) {
                    pausedTotalNs += System.nanoTime() - pausedAtNs;
                    pausedAtNs = 0L;
                }
                long now = System.nanoTime();
                if (now - lastSubmitNs < frameIntervalNs) {
                    Thread.sleep(2L);
                    continue;
                }
                rgba.clear();
                if (!JREUtils.copyLatestFrame(rgba, info) || info[3] == lastSequence) {
                    Thread.sleep(5L);
                    continue;
                }
                if (info[0] < frameWidth || info[1] < frameHeight || info[2] != rowStride) {
                    Thread.sleep(5L);
                    continue;
                }
                lastSequence = info[3];
                i420.clear();
                OsmesaFrameConverter.rgbxToI420(rgba, frameWidth, frameHeight, rowStride, i420);
                i420.flip();
                long ptsUs = Math.max(0L, (now - startNs - pausedTotalNs) / 1000L);
                encoder.encodeFrame(i420, ptsUs);
                lastSubmitNs = now;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!stopRequested) workerFailure = new IOException("Gameplay capture thread interrupted", e);
        } catch (IOException | RuntimeException e) {
            workerFailure = e instanceof IOException ? (IOException) e
                    : new IOException("Gameplay frame capture failed", e);
        } finally {
            JREUtils.setFrameCaptureEnabled(false);
            if (encoder != null) {
                try { encoder.stop(); }
                catch (IOException e) {
                    if (workerFailure == null) workerFailure = e;
                    else Log.e(TAG, "MP4 finalization also failed", e);
                }
                encoder = null;
            }
            synchronized (stateLock) {
                running = false;
                paused = false;
                if (worker == Thread.currentThread()) worker = null;
            }
            if (workerFailure != null && listener != null) listener.onRecordingError(workerFailure);
        }
    }

    @Override
    public void close() throws IOException {
        stop();
    }
}
