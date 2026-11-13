package com.willh.wz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owner-approved MediaProjection lifecycle. Frames are currently discarded after capture;
 * this service does not transmit screen contents until the WebRTC video track is integrated.
 */
public class RemoteCaptureService extends Service {
    public static final String ACTION_START = "com.willh.wz.action.START_REMOTE_CAPTURE";
    public static final String ACTION_STOP = "com.willh.wz.action.STOP_REMOTE_CAPTURE";
    public static final String EXTRA_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_RESULT_DATA = "projection_result_data";
    public static final String EXTRA_SESSION_ID = "session_id";

    private static final String TAG = "GamePilotCapture";
    private static final String CHANNEL_ID = "gamepilot_screen_share";
    private static final int NOTIFICATION_ID = 7310;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread frameThread;
    private String sessionId;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopCapture(true);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (projection != null) return START_NOT_STICKY;

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        startCaptureForeground();
        try {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultData == null || sessionId == null || sessionId.isEmpty()) {
                throw new IllegalArgumentException("缺少屏幕采集授权数据");
            }
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new IllegalStateException("系统未授予屏幕采集权限");

            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    projection = null;
                    stopCapture(true);
                }
            }, new Handler(getMainLooper()));
            startVirtualDisplay();
            Log.i(TAG, "Owner-approved screen capture started for session " + sessionId);
        } catch (Exception error) {
            Log.e(TAG, "Unable to start MediaProjection", error);
            stopCapture(true);
        }
        return START_NOT_STICKY;
    }

    private void startCaptureForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "GamePilot 屏幕共享", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("设备正在进行用户主动授权的屏幕采集");
            manager.createNotificationChannel(channel);
        }

        Intent stopIntent = new Intent(this, RemoteCaptureService.class).setAction(ACTION_STOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopPending = PendingIntent.getService(this, 7311, stopIntent, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("GamePilot 屏幕采集已开启")
                .setContentText("仅在本机主人批准的会话期间运行")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "停止共享", stopPending).build());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, builder.build());
        }
    }

    private void startVirtualDisplay() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        int width = Math.max(metrics.widthPixels, 1);
        int height = Math.max(metrics.heightPixels, 1);
        int density = metrics.densityDpi;

        frameThread = new HandlerThread("GamePilotScreenFrames");
        frameThread.start();
        imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                // Placeholder sink: close frames immediately until WebRTC is connected.
            } catch (IllegalStateException ignored) {
            } finally {
                if (image != null) image.close();
            }
        }, new Handler(frameThread.getLooper()));

        virtualDisplay = projection.createVirtualDisplay(
                "GamePilotOwnerApprovedCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                null
        );
        if (virtualDisplay == null) throw new IllegalStateException("系统无法创建屏幕采集显示器");
    }

    private void stopCapture(boolean endRemoteSession) {
        final String endedSession = sessionId;
        sessionId = null;
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (frameThread != null) {
            frameThread.quitSafely();
            frameThread = null;
        }
        if (projection != null) {
            MediaProjection old = projection;
            projection = null;
            old.stop();
        }
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        stopForeground(true);

        if (endRemoteSession && endedSession != null) {
            new Thread(() -> {
                try {
                    RemoteDeviceStore.DeviceRecord record = RemoteDeviceStore.load(this);
                    if (record != null) RemoteApiClient.endSession(record.serverUrl, record.deviceToken, endedSession);
                } catch (Exception error) {
                    Log.w(TAG, "Could not notify server that the owner ended the session");
                }
            }, "GamePilotEndSession").start();
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (projection != null || virtualDisplay != null || imageReader != null) stopCapture(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
