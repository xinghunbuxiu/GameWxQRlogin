package com.willh.wz;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owner-approved screen-sharing foreground service.
 * MediaProjection is handed to WebRTC's ScreenCapturerAndroid; screen frames are sent
 * only over an approved WebRTC PeerConnection.
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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private String sessionId;
    private Intent projectionPermissionData;
    private RemoteSignalingClient signalingClient;
    private RemoteWebRtcSession webRtcSession;
    private boolean sessionStarted;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopCapture(true);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || sessionId != null) return START_NOT_STICKY;

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        projectionPermissionData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        if (sessionId == null || sessionId.isEmpty() || projectionPermissionData == null || resultCode != Activity.RESULT_OK) {
            stopCapture(true);
            return START_NOT_STICKY;
        }

        startCaptureForeground();
        connectSignalingAndStartWebRtc();
        return START_NOT_STICKY;
    }

    private void startCaptureForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "GamePilot 屏幕共享", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("设备正在进行用户主动授权的屏幕共享");
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
                .setContentTitle("GamePilot 屏幕共享已开启")
                .setContentText("画面仅发送给本次已批准的远程会话")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "停止共享", stopPending).build());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, builder.build());
        }
    }

    private void connectSignalingAndStartWebRtc() {
        new Thread(() -> {
            try {
                RemoteDeviceStore.DeviceRecord record = RemoteDeviceStore.load(this);
                if (record == null) throw new IllegalStateException("设备凭证不存在");

                RemoteSignalingClient client = new RemoteSignalingClient();
                signalingClient = client;
                client.connect(record.serverUrl, record.deviceToken, new RemoteSignalingClient.Listener() {
                    @Override public void onConnected() {
                        mainHandler.post(() -> {
                            if (stopping.get()) return;
                            DisplayMetrics metrics = getResources().getDisplayMetrics();
                            int width = Math.max(metrics.widthPixels, 1);
                            int height = Math.max(metrics.heightPixels, 1);
                            webRtcSession = new RemoteWebRtcSession(
                                    RemoteCaptureService.this,
                                    sessionId,
                                    projectionPermissionData,
                                    client,
                                    new RemoteWebRtcSession.Listener() {
                                        @Override public void onConnected() {
                                            if (!sessionStarted) {
                                                sessionStarted = true;
                                                client.sendSessionStarted(sessionId);
                                                Log.i(TAG, "WebRTC screen session connected");
                                            }
                                        }
                                        @Override public void onFailure(String message) {
                                            Log.w(TAG, message);
                                            stopCapture(true);
                                        }
                                    },
                                    width,
                                    height,
                                    15
                            );
                            webRtcSession.start();
                        });
                    }

                    @Override public void onSessionRequested(String requestedSessionId, String requesterId, String reason) {
                        // A second request is not accepted while this foreground session is active.
                    }

                    @Override public void onSignal(String incomingSessionId, JSONObject payload) {
                        if (!incomingSessionId.equals(sessionId)) return;
                        RemoteWebRtcSession current = webRtcSession;
                        if (current != null) current.handleSignal(payload);
                    }

                    @Override public void onDisconnected(String message) {
                        Log.w(TAG, message);
                        stopCapture(true);
                    }
                });
            } catch (Exception error) {
                Log.e(TAG, "Unable to connect signaling for approved session", error);
                stopCapture(true);
            }
        }, "GamePilotWebRtcBootstrap").start();
    }

    private void stopCapture(boolean endRemoteSession) {
        mainHandler.post(() -> stopCaptureOnMain(endRemoteSession));
    }

    private void stopCaptureOnMain(boolean endRemoteSession) {
        if (!stopping.compareAndSet(false, true)) return;
        final String endedSession = sessionId;
        sessionId = null;

        RemoteWebRtcSession oldSession = webRtcSession;
        webRtcSession = null;
        RemoteSignalingClient oldSignaling = signalingClient;
        signalingClient = null;
        projectionPermissionData = null;

        if (oldSession != null) {
            new Thread(oldSession::release, "GamePilotWebRtcRelease").start();
        }
        if (oldSignaling != null) oldSignaling.close();

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
        stopCapture(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
