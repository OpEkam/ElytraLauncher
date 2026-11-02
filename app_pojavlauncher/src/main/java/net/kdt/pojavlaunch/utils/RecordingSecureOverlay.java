package net.kdt.pojavlaunch.utils;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

/**
 * Places one small control in a FLAG_SECURE application panel so Android
 * screen recording excludes that control without securing the game surface.
 */
public final class RecordingSecureOverlay {
    private static final String TAG = "RecordingSecureOverlay";

    private final Activity activity;
    private final View overlayView;
    private final int width;
    private final int height;
    private final WindowManager windowManager;
    private ViewGroup originalParent;
    private ViewGroup.LayoutParams originalLayoutParams;
    private boolean attached;

    public RecordingSecureOverlay(Activity activity, View overlayView, int widthDp, int heightDp) {
        this.activity = activity;
        this.overlayView = overlayView;
        float density = activity.getResources().getDisplayMetrics().density;
        this.width = (int) (widthDp * density + 0.5f);
        this.height = (int) (heightDp * density + 0.5f);
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public void attach() {
        if (attached || activity.isFinishing() || activity.isDestroyed()) return;
        if (overlayView.getVisibility() != View.VISIBLE) return;
        if (!(overlayView.getParent() instanceof ViewGroup)) {
            Log.w(TAG, "Control has no ViewGroup parent");
            return;
        }

        originalParent = (ViewGroup) overlayView.getParent();
        originalLayoutParams = overlayView.getLayoutParams();
        originalParent.removeView(overlayView);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_SECURE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL;
        params.token = activity.getWindow().getDecorView().getWindowToken();
        params.setTitle("ElytraLauncher recording-protected drawer control");

        try {
            windowManager.addView(overlayView, params);
            attached = true;
            Log.i(TAG, "Secure drawer control panel attached");
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not attach secure panel; restoring control", error);
            restoreToActivity();
        }
    }

    public void detach() {
        if (attached) {
            try {
                windowManager.removeView(overlayView);
            } catch (RuntimeException error) {
                Log.w(TAG, "Secure panel was already detached", error);
            }
            attached = false;
        }
        restoreToActivity();
    }

    private void restoreToActivity() {
        if (overlayView.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlayView.getParent()).removeView(overlayView);
        }
        if (originalParent != null && overlayView.getParent() == null) {
            originalParent.addView(overlayView, originalLayoutParams);
        }
        originalParent = null;
        originalLayoutParams = null;
    }
}
