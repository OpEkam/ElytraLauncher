package net.kdt.pojavlaunch.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

/**
 * Owns the user's MediaProjection grant for gameplay audio capture.
 *
 * This service deliberately runs in :game, the same process as MainActivity
 * and GameplayFrameRecorder. The projection is never parceled across processes.
 */
public final class GameplayMediaProjectionService extends Service {
    public static final String EXTRA_RESULT_CODE = "elytra.projection.RESULT_CODE";
    public static final String EXTRA_RESULT_DATA = "elytra.projection.RESULT_DATA";
    private static final int NOTIFICATION_ID = 7402;

    private final LocalBinder binder = new LocalBinder();
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;

    public final class LocalBinder extends Binder {
        public GameplayMediaProjectionService getService() {
            return GameplayMediaProjectionService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Tools.buildNotificationChannel(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (intent == null || !intent.hasExtra(EXTRA_RESULT_DATA)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        Intent openApp = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 7402, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification notification = new NotificationCompat.Builder(this, "channel_id")
                .setContentTitle("ElytraLauncher gameplay recording")
                .setContentText("Capturing permitted game audio")
                .setSmallIcon(R.drawable.notif_icon)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setNotificationSilent()
                .build();

        try {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (manager == null || resultData == null) {
                throw new IllegalStateException("MediaProjection permission data is missing");
            }
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                throw new IllegalStateException("Android did not provide a MediaProjection");
            }
            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    projection = null;
                    stopSelf();
                }
            };
            projection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));
        } catch (RuntimeException error) {
            stopForeground(true);
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /** Returns the active projection only after the consent result was accepted. */
    @Nullable
    public MediaProjection getProjection() {
        return projection;
    }

    @Override
    public void onDestroy() {
        MediaProjection active = projection;
        projection = null;
        if (active != null) {
            try {
                if (projectionCallback != null) active.unregisterCallback(projectionCallback);
            } catch (RuntimeException ignored) {}
            try { active.stop(); } catch (RuntimeException ignored) {}
        }
        projectionCallback = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }
}
