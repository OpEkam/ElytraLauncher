package net.kdt.pojavlaunch.utils;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.Window;
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
            if (isNearlyBlack(bitmap) && captureWindowComposite()) {
                Log.w(TAG, "SurfaceView PixelCopy was black; using cropped window capture");
            }
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
            if (isNearlyBlack(bitmap) && captureWindowComposite()) {
                Log.w(TAG, "TextureView bitmap was black; using cropped window capture");
            }
            return bitmap;
        }
        throw new IOException("Unsupported Minecraft rendering surface");
    }

    /**
     * Some Android GPU/renderer combinations return an all-black bitmap when
     * copying the individual game surface. Detect that case and retry from the
     * containing window, cropped to the exact Minecraft surface bounds.
     */
    private boolean captureWindowComposite() throws IOException, InterruptedException {
        if (android.os.Build.VERSION.SDK_INT < 26) return false;
        Activity activity = findActivity(source.getContext());
        if (activity == null) return false;

        final Rect[] sourceRect = new Rect[1];
        final CountDownLatch rectLatch = new CountDownLatch(1);
        Runnable readRect = () -> {
            int[] location = new int[2];
            source.getLocationInWindow(location);
            sourceRect[0] = new Rect(location[0], location[1],
                    location[0] + bitmap.getWidth(), location[1] + bitmap.getHeight());
            rectLatch.countDown();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) readRect.run();
        else if (!source.post(readRect)) return false;
        if (!rectLatch.await(1500L, TimeUnit.MILLISECONDS) || sourceRect[0] == null) return false;

        final CountDownLatch copyLatch = new CountDownLatch(1);
        final int[] result = new int[]{PixelCopy.ERROR_UNKNOWN};
        PixelCopy.request(activity.getWindow(), sourceRect[0], bitmap, copyResult -> {
            result[0] = copyResult;
            copyLatch.countDown();
        }, mainHandler);
        if (!copyLatch.await(1500L, TimeUnit.MILLISECONDS) || result[0] != PixelCopy.SUCCESS) {
            Log.w(TAG, "Cropped window PixelCopy failed: " + result[0]);
            return false;
        }
        logFrameSample("Window-cropped fallback", bitmap);
        return !isNearlyBlack(bitmap);
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    private static boolean isNearlyBlack(Bitmap frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        int stepX = Math.max(1, width / 16);
        int stepY = Math.max(1, height / 16);
        long total = 0L;
        int brightest = 0;
        int samples = 0;
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int pixel = frame.getPixel(x, y);
                int luma = (((pixel >> 16) & 0xff) * 30
                        + ((pixel >> 8) & 0xff) * 59
                        + (pixel & 0xff) * 11) / 100;
                total += luma;
                brightest = Math.max(brightest, luma);
                samples++;
            }
        }
        return samples > 0 && total / samples < 2 && brightest < 8;
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
