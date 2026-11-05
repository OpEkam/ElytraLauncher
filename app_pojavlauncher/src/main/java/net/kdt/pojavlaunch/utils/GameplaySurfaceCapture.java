package net.kdt.pojavlaunch.utils;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Captures only Minecraft's rendering surface, rather than the whole launcher
 * window. This keeps launcher touch controls and other Android overlays out
 * of the recorded frames.
 */
public final class GameplaySurfaceCapture implements AutoCloseable {
    private static final String TAG = "GameplaySurfaceCapture";
    private final View source;
    private final Bitmap bitmap;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private GameplaySurfaceCapture(View source) throws IOException {
        this.source = source;
        int width = source.getWidth() & ~1;
        int height = source.getHeight() & ~1;
        if (width < 2 || height < 2) {
            throw new IOException("Minecraft rendering surface has no usable size");
        }
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    public static boolean isSupported(View source) {
        if (source instanceof TextureView) return true;
        return source instanceof SurfaceView && android.os.Build.VERSION.SDK_INT >= 24;
    }

    public static GameplaySurfaceCapture create(View source) throws IOException {
        if (source == null || !isSupported(source)) return null;
        return new GameplaySurfaceCapture(source);
    }

    /** Copies the latest game-only frame into a reusable ARGB bitmap. */
    public Bitmap capture() throws IOException, InterruptedException {
        if (!source.isAttachedToWindow()) {
            throw new IOException("Minecraft rendering surface is not attached");
        }

        if (source instanceof SurfaceView) {
            final CountDownLatch latch = new CountDownLatch(1);
            final int[] result = new int[]{PixelCopy.ERROR_UNKNOWN};
            PixelCopy.request((SurfaceView) source, bitmap, copyResult -> {
                result[0] = copyResult;
                latch.countDown();
            }, mainHandler);
            if (!latch.await(1500L, TimeUnit.MILLISECONDS)) {
                throw new IOException("Timed out copying Minecraft SurfaceView");
            }
            if (result[0] != PixelCopy.SUCCESS) {
                throw new IOException("PixelCopy could not capture the game surface (code "
                        + result[0] + ")");
            }
            logFrameSample("SurfaceView", bitmap);
            return bitmap;
        }

        if (source instanceof TextureView) {
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] success = new boolean[1];
            Runnable copy = () -> {
                try {
                    Bitmap captured = ((TextureView) source).getBitmap(bitmap);
                    success[0] = captured != null;
                } finally {
                    latch.countDown();
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) copy.run();
            else if (!source.post(copy)) throw new IOException("Could not schedule TextureView capture");
            if (!latch.await(1500L, TimeUnit.MILLISECONDS) || !success[0]) {
                throw new IOException("Could not copy Minecraft TextureView");
            }
            logFrameSample("TextureView", bitmap);
            return bitmap;
        }
        throw new IOException("Unsupported Minecraft rendering surface");
    }

    private static void logFrameSample(String type, Bitmap frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        int stepX = Math.max(1, width / 16);
        int stepY = Math.max(1, height / 16);
        long luminanceTotal = 0L;
        int brightest = 0;
        int samples = 0;
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int pixel = frame.getPixel(x, y);
                int luminance = (((pixel >> 16) & 0xff) * 30
                        + ((pixel >> 8) & 0xff) * 59
                        + (pixel & 0xff) * 11) / 100;
                luminanceTotal += luminance;
                brightest = Math.max(brightest, luminance);
                samples++;
            }
        }
        int average = samples == 0 ? 0 : (int) (luminanceTotal / samples);
        Log.i(TAG, type + " frame sample: averageLuma=" + average
                + ", brightestLuma=" + brightest + ", samples=" + samples);
    }

    @Override
    public void close() {
        if (!bitmap.isRecycled()) bitmap.recycle();
    }
}
