package net.kdt.pojavlaunch.utils;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds touch-through secure panels over the control views. The original controls
 * stay in ControlLayout, so their layout, drawing, and touch handling are unchanged.
 */
public final class RecordingSecureOverlay {
    private static final String TAG = "RecordingSecureOverlay";
    private final Activity activity;
    private final View drawerButton;
    private final WindowManager windowManager;
    private final List<Mask> masks = new ArrayList<>();
    private ControlLayout controls;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private boolean attached;

    public RecordingSecureOverlay(Activity activity, View drawerButton, int widthDp, int heightDp) {
        this.activity = activity;
        this.drawerButton = drawerButton;
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public void attach() {
        if (attached || activity.isFinishing() || activity.isDestroyed()) return;
        controls = activity.findViewById(R.id.main_control_layout);
        if (controls == null) {
            Log.w(TAG, "ControlLayout not found; secure masks not attached");
            return;
        }
        attached = true;
        layoutListener = this::syncMasks;
        controls.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        syncMasks();
        Log.i(TAG, "Secure control masks attached");
    }

    private void syncMasks() {
        if (!attached || activity.isFinishing() || activity.isDestroyed()) return;
        List<View> targets = new ArrayList<>();
        for (ControlInterface control : controls.getButtonChildren()) {
            View view = control.getControlView();
            if (view != null) targets.add(view);
        }
        if (drawerButton != null && drawerButton.getVisibility() == View.VISIBLE) {
            targets.add(drawerButton);
        }

        for (int i = masks.size() - 1; i >= 0; i--) {
            Mask mask = masks.get(i);
            if (!targets.contains(mask.target)) {
                removeMask(mask);
                masks.remove(i);
            }
        }
        for (View target : targets) {
            Mask mask = findMask(target);
            if (mask == null) {
                mask = new Mask(target);
                masks.add(mask);
            }
            updateMask(mask);
        }
    }

    private Mask findMask(View target) {
        for (Mask mask : masks) if (mask.target == target) return mask;
        return null;
    }

    private void updateMask(Mask mask) {
        View target = mask.target;
        if (target.getVisibility() != View.VISIBLE || target.getWidth() <= 0
                || target.getHeight() <= 0 || !target.isShown()) {
            if (mask.added) {
                mask.params.width = 1;
                mask.params.height = 1;
                mask.params.x = -10000;
                mask.params.y = -10000;
                try { windowManager.updateViewLayout(mask.view, mask.params); }
                catch (RuntimeException ignored) { }
            }
            return;
        }

        int[] location = new int[2];
        target.getLocationInWindow(location);
        int width = target.getWidth();
        int height = target.getHeight();
        if (mask.params == null) {
            mask.params = new WindowManager.LayoutParams(
                    width, height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_SECURE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            mask.params.gravity = Gravity.TOP | Gravity.LEFT;
            mask.params.token = activity.getWindow().getDecorView().getWindowToken();
            mask.params.setTitle("ElytraLauncher secure recording control mask");
        }
        mask.params.x = location[0];
        mask.params.y = location[1];
        mask.params.width = width;
        mask.params.height = height;
        try {
            if (mask.added) windowManager.updateViewLayout(mask.view, mask.params);
            else {
                windowManager.addView(mask.view, mask.params);
                mask.added = true;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to position secure mask", error);
        }
    }

    public void detach() {
        attached = false;
        if (controls != null && layoutListener != null) {
            ViewTreeObserver observer = controls.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(layoutListener);
        }
        for (Mask mask : masks) removeMask(mask);
        masks.clear();
        controls = null;
        layoutListener = null;
    }

    private void removeMask(Mask mask) {
        if (mask.added) {
            try { windowManager.removeView(mask.view); }
            catch (RuntimeException error) { Log.w(TAG, "Mask already removed", error); }
            mask.added = false;
        }
    }

    private static final class Mask {
        final View target;
        final View view;
        WindowManager.LayoutParams params;
        boolean added;

        Mask(View target) {
            this.target = target;
            this.view = new View(target.getContext());
            // Fully transparent on the physical display; FLAG_SECURE protects its region
            // from Android's normal screen-capture pipeline.
            this.view.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            this.view.setClickable(false);
            this.view.setFocusable(false);
        }
    }
}
