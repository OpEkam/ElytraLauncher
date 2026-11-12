package net.kdt.pojavlaunch.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.projection.MediaProjection;
import android.util.Log;
import android.view.View;
import android.graphics.Rect;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Captures Minecraft frames from its renderer surface where Android exposes a
 * capturable SurfaceView/TextureView, with the native OSMesa frame path as a
 * fallback. Encodes the frames as H.264 and optionally muxes captured audio.
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
    private GameplayAudioRecorder gameplayAudioRecorder;
    private File videoTempFile;
    private File audioTempFile;
    private boolean includeAudio;
    private View captureView;

    public GameplayFrameRecorder(Listener listener) {
        this.listener = listener;
    }

    /** Starts asynchronously. The MP4 is created after the first valid gameplay frame. */
    public void start(File output, int requestedBitRate, int requestedFrameRate) throws IOException {
        start(output, requestedBitRate, requestedFrameRate, false);
    }

    /** Starts gameplay capture with optional microphone audio. */
    public void start(File output, int requestedBitRate, int requestedFrameRate,
                      boolean recordMicrophone) throws IOException {
        start(output, requestedBitRate, requestedFrameRate, null, recordMicrophone);
    }

    /** Starts recording from the renderer surface with optional mixed game and microphone audio. */
    public void start(File output, int requestedBitRate, int requestedFrameRate,
                      View rendererSurface, boolean recordMicrophone) throws IOException {
        start(output, requestedBitRate, requestedFrameRate, rendererSurface, recordMicrophone, null);
    }

    /** Starts capture using a user-approved MediaProjection for Android playback audio. */
    public void start(File output, int requestedBitRate, int requestedFrameRate,
                      View rendererSurface, boolean recordMicrophone,
                      MediaProjection projection) throws IOException {
        if (output == null || requestedBitRate < 1 || requestedFrameRate < 1) {
            throw new IllegalArgumentException("Output file and valid encoding settings are required");
        }
        synchronized (stateLock) {
            if (running || worker != null) throw new IllegalStateException("Recorder is already active");
            outputFile = output;
            includeAudio = recordMicrophone || projection != null;
            captureView = rendererSurface;
            videoTempFile = includeAudio
                    ? new File(output.getParentFile(), output.getName() + ".video.mp4") : output;
            audioTempFile = includeAudio
                    ? new File(output.getParentFile(), output.getName() + ".audio.mp4") : null;
            if (includeAudio) {
                gameplayAudioRecorder = new GameplayAudioRecorder();
                try {
                    gameplayAudioRecorder.start(audioTempFile, projection, recordMicrophone);
                } catch (IOException error) {
                    gameplayAudioRecorder = null;
                    throw error;
                } catch (RuntimeException error) {
                    gameplayAudioRecorder = null;
                    throw new IOException("Could not start gameplay audio recording", error);
                }
            }
            bitRate = requestedBitRate;
            frameRate = requestedFrameRate;
            resetPerformanceLog();
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
            if (running) {
                paused = true;
                if (gameplayAudioRecorder != null) gameplayAudioRecorder.pause();
            }
        }
    }

    public void resume() {
        synchronized (stateLock) {
            if (running) {
                paused = false;
                if (gameplayAudioRecorder != null) gameplayAudioRecorder.resume();
            }
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
            if (captureView != null && GameplaySurfaceCapture.isSupported(captureView)) {
                captureSurfaceFrames();
                return;
            }
            // The first small-buffer call obtains frame metadata even though it cannot
            // copy pixels; allocate the exact native stride and retry.
            while (!stopRequested && System.currentTimeMillis() < deadline) {
                info[0] = info[1] = info[2] = info[3] = 0;
                JREUtils.copyLatestFrame(probe, info);
                if (info[0] > 1 && info[1] > 1 && info[2] >= info[0]) break;
                Thread.sleep(30L);
            }
            if (info[0] <= 1 || info[1] <= 1 || info[2] < info[0]) {
                throw new IOException("No gameplay frames received from this renderer; its surface may not support capture");
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
            encoder.start(videoTempFile, frameWidth, frameHeight, bitRate, frameRate);
            startNs = System.nanoTime();
            lastSequence = info[3];
            long metricWindowStartNs = startNs;
            long captureTotalNs = 0L, convertTotalNs = 0L, encodeTotalNs = 0L;
            int metricFrames = 0, metricEncoded = 0, metricDropped = 0;
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
                long captureStartNs = System.nanoTime();
                boolean frameCopied = JREUtils.copyLatestFrame(rgba, info);
                captureTotalNs += System.nanoTime() - captureStartNs;
                if (!frameCopied || info[3] == lastSequence) {
                    Thread.sleep(5L);
                    continue;
                }
                if (info[0] < frameWidth || info[1] < frameHeight || info[2] != rowStride) {
                    Thread.sleep(5L);
                    continue;
                }
                lastSequence = info[3];
                metricFrames++;
                i420.clear();
                long convertStartNs = System.nanoTime();
                OsmesaFrameConverter.rgbxToI420(rgba, frameWidth, frameHeight, rowStride, i420);
                convertTotalNs += System.nanoTime() - convertStartNs;
                i420.flip();
                long ptsUs = Math.max(0L, (now - startNs - pausedTotalNs) / 1000L);
                long encodeStartNs = System.nanoTime();
                if (encoder.encodeFrame(i420, ptsUs)) metricEncoded++;
                else metricDropped++;
                encodeTotalNs += System.nanoTime() - encodeStartNs;
                lastSubmitNs = now;

                long metricNowNs = System.nanoTime();
                if (metricNowNs - metricWindowStartNs >= 5_000_000_000L) {
                    long divisor = Math.max(1, metricFrames);
                    writePerformanceStats("path=native-frame-copy, size=" + frameWidth + "x" + frameHeight
                            + ", captured=" + metricFrames + ", encoded=" + metricEncoded
                            + ", dropped=" + metricDropped
                            + ", avgCaptureMs=" + (captureTotalNs / divisor / 1_000_000.0)
                            + ", avgConvertMs=" + (convertTotalNs / divisor / 1_000_000.0)
                            + ", avgEncoderSubmitMs=" + (encodeTotalNs / divisor / 1_000_000.0));
                    metricWindowStartNs = metricNowNs;
                    captureTotalNs = convertTotalNs = encodeTotalNs = 0L;
                    metricFrames = metricEncoded = metricDropped = 0;
                }
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
            if (gameplayAudioRecorder != null) {
                try { gameplayAudioRecorder.stop(); }
                catch (IOException e) {
                    if (workerFailure == null) workerFailure = e;
                    else Log.e(TAG, "Audio finalization also failed", e);
                }
                gameplayAudioRecorder = null;
            }
            if (includeAudio && workerFailure == null) {
                try { Mp4TrackMuxer.combine(videoTempFile, audioTempFile, outputFile); }
                catch (IOException e) { workerFailure = e; }
            }
            if (includeAudio) {
                if (videoTempFile != null) videoTempFile.delete();
                if (audioTempFile != null) audioTempFile.delete();
            }
            synchronized (stateLock) {
                running = false;
                paused = false;
                if (worker == Thread.currentThread()) worker = null;
            }
            if (workerFailure != null && listener != null) listener.onRecordingError(workerFailure);
        }
    }


    /**
     * Captures the Minecraft SurfaceView with PixelCopy or TextureView with
     * getBitmap. Both paths copy only the game surface, excluding launcher overlays.
     */
    private void captureSurfaceFrames() throws IOException, InterruptedException {
        try (GameplaySurfaceCapture capture = GameplaySurfaceCapture.create(captureView)) {
            Bitmap firstFrame = null;
            IOException firstFrameError = null;
            long firstFrameDeadline = System.currentTimeMillis() + 5000L;
            while (!stopRequested && firstFrame == null
                    && System.currentTimeMillis() < firstFrameDeadline) {
                try {
                    firstFrame = capture.capture();
                } catch (IOException error) {
                    firstFrameError = error;
                    Thread.sleep(100L);
                }
            }
            if (firstFrame == null) {
                throw new IOException("Could not obtain a valid Minecraft frame from this renderer surface",
                        firstFrameError);
            }

            int sourceWidth = firstFrame.getWidth();
            int sourceHeight = firstFrame.getHeight();
            if (sourceWidth < 2 || sourceHeight < 2) {
                throw new IOException("Minecraft surface returned an invalid frame size");
            }
            // Cap SurfaceView/TextureView recording at 720p. Full device-resolution
            // readback, RGB->YUV conversion and H.264 encoding are unnecessarily
            // expensive on mid-range phones and can starve Minecraft's renderer.
            float scale = Math.min(1f, Math.min(1280f / sourceWidth, 720f / sourceHeight));
            int width = Math.max(2, ((int) (sourceWidth * scale)) & ~1);
            int height = Math.max(2, ((int) (sourceHeight * scale)) & ~1);
            Bitmap lastGoodFrame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas frameCacheCanvas = new Canvas(lastGoodFrame);
            Rect targetRect = new Rect(0, 0, width, height);
            frameCacheCanvas.drawBitmap(firstFrame, null, targetRect, null);

            try {
                ByteBuffer i420 = ByteBuffer.allocateDirect(width * height * 3 / 2);
                int[] row0 = new int[width];
                int[] row1 = new int[width];
                encoder = new I420VideoEncoder();
                encoder.start(videoTempFile, width, height, bitRate, frameRate);

                long startNs = System.nanoTime();
                long pausedAtNs = 0L;
                long pausedTotalNs = 0L;
                long lastSubmitNs = 0L;
                long frameIntervalNs = 1_000_000_000L / Math.max(1, frameRate);
                int consecutiveCaptureFailures = 0;
                long metricWindowStartNs = startNs;
                long captureTotalNs = 0L, scaleTotalNs = 0L;
                long convertTotalNs = 0L, encodeTotalNs = 0L;
                int metricFrames = 0, metricEncoded = 0, metricDropped = 0;

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

                    Bitmap frame;
                    long captureStartNs = System.nanoTime();
                    try {
                        frame = capture.capture();
                        captureTotalNs += System.nanoTime() - captureStartNs;
                        long scaleStartNs = System.nanoTime();
                        frameCacheCanvas.drawBitmap(frame, null, targetRect, null);
                        scaleTotalNs += System.nanoTime() - scaleStartNs;
                        consecutiveCaptureFailures = 0;
                    } catch (IOException captureError) {
                        consecutiveCaptureFailures++;
                        if (consecutiveCaptureFailures >= 60) {
                            throw new IOException("Minecraft surface has stopped providing valid frames for about two seconds",
                                    captureError);
                        }
                        if (consecutiveCaptureFailures == 1 || consecutiveCaptureFailures % 30 == 0) {
                            Log.w(TAG, "Skipping invalid renderer frame; reusing last valid frame ("
                                    + consecutiveCaptureFailures + " consecutive failures)", captureError);
                        }
                        // Keep the video stream valid without recording black frames or the
                        // whole launcher window (which would include touch controls).
                        frame = lastGoodFrame;
                    }
                    if (frame == lastGoodFrame) {
                        captureTotalNs += System.nanoTime() - captureStartNs;
                    }
                    i420.clear();
                    long convertStartNs = System.nanoTime();
                    OsmesaFrameConverter.bitmapToI420(frame, width, height, i420, row0, row1);
                    convertTotalNs += System.nanoTime() - convertStartNs;
                    i420.flip();
                    long ptsUs = Math.max(0L, (now - startNs - pausedTotalNs) / 1000L);
                    long encodeStartNs = System.nanoTime();
                    if (encoder.encodeFrame(i420, ptsUs)) metricEncoded++;
                    else metricDropped++;
                    encodeTotalNs += System.nanoTime() - encodeStartNs;
                    metricFrames++;
                    lastSubmitNs = now;

                    long metricNowNs = System.nanoTime();
                    if (metricNowNs - metricWindowStartNs >= 5_000_000_000L) {
                        long divisor = Math.max(1, metricFrames);
                        writePerformanceStats("path=surface-capture, size=" + width + "x" + height
                                + ", captured=" + metricFrames + ", encoded=" + metricEncoded
                                + ", dropped=" + metricDropped
                                + ", captureFailures=" + consecutiveCaptureFailures
                                + ", avgCaptureMs=" + (captureTotalNs / divisor / 1_000_000.0)
                                + ", avgScaleMs=" + (scaleTotalNs / divisor / 1_000_000.0)
                                + ", avgConvertMs=" + (convertTotalNs / divisor / 1_000_000.0)
                                + ", avgEncoderSubmitMs=" + (encodeTotalNs / divisor / 1_000_000.0));
                        metricWindowStartNs = metricNowNs;
                        captureTotalNs = scaleTotalNs = convertTotalNs = encodeTotalNs = 0L;
                        metricFrames = metricEncoded = metricDropped = 0;
                    }
                }
            } finally {
                if (!lastGoodFrame.isRecycled()) lastGoodFrame.recycle();
            }
        }
    }

    private void resetPerformanceLog() {
        if (outputFile == null) return;
        File statsFile = new File(outputFile.getAbsolutePath() + ".performance.txt");
        try (FileWriter writer = new FileWriter(statsFile, false)) {
            writer.write("ElytraLauncher recording performance diagnostics\n");
            writer.write("Target FPS=" + frameRate + "\n");
        } catch (IOException error) {
            Log.w(TAG, "Could not create recording performance log", error);
        }
    }

    private void writePerformanceStats(String sample) {
        Log.i(TAG, "Recorder performance: " + sample);
        if (outputFile == null) return;
        File statsFile = new File(outputFile.getAbsolutePath() + ".performance.txt");
        try (FileWriter writer = new FileWriter(statsFile, true)) {
            writer.write(sample);
            writer.write("\n");
        } catch (IOException error) {
            Log.w(TAG, "Could not save recording performance sample", error);
        }
    }

    @Override
    public void close() throws IOException {
        stop();
    }
}
