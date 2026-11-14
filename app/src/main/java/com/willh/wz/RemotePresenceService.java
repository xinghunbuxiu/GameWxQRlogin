package com.willh.wz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Explicitly-started foreground service that owns device presence independently of UI lifecycle.
 * It maintains heartbeats and the owner-approval WSS listener; it never starts screen capture.
 */
public final class RemotePresenceService extends Service {
    public static final String ACTION_START = "com.willh.wz.action.START_REMOTE_PRESENCE";
    public static final String ACTION_STOP = "com.willh.wz.action.STOP_REMOTE_PRESENCE";
    public static final String ACTION_STATUS = "com.willh.wz.action.REMOTE_PRESENCE_STATUS";
    public static final String ACTION_SESSION_REQUESTED = "com.willh.wz.action.REMOTE_SESSION_REQUESTED";
    public static final String EXTRA_RUNNING = "presence_running";
    public static final String EXTRA_STATUS = "presence_status";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_REQUESTER_ID = "requester_id";
    public static final String EXTRA_REASON = "request_reason";
    public static final String EXTRA_PENDING_SESSION_ID = "pending_session_id";
    public static final String EXTRA_PENDING_REQUESTER_ID = "pending_requester_id";
    public static final String EXTRA_PENDING_REASON = "pending_reason";

    private static final String TAG = "GamePilotPresence";
    private static final String CHANNEL_ID = "gamepilot_device_presence";
    private static final int NOTIFICATION_ID = 7312;
    private static final long HEARTBEAT_SECONDS = 60L;
    private static final long INITIAL_RECONNECT_SECONDS = 5L;
    private static final long MAX_RECONNECT_SECONDS = 300L;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger connectionGeneration = new AtomicInteger(0);
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> reconnectTask;
    private RemoteSignalingClient signalingClient;
    private long reconnectDelaySeconds = INITIAL_RECONNECT_SECONDS;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopPresence();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (running.compareAndSet(false, true)) {
            startPresenceForeground();
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "GamePilotPresence");
                thread.setDaemon(false);
                return thread;
            });
            broadcastStatus("在线服务已启动，正在连接云端");
            scheduler.execute(this::connectSignaling);
            heartbeatTask = scheduler.scheduleWithFixedDelay(
                    this::sendHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }

    private void connectSignaling() {
        if (!running.get()) return;
        final int generation = connectionGeneration.incrementAndGet();
        try {
            RemoteDeviceStore.DeviceRecord record = RemoteDeviceStore.load(this);
            if (record == null) throw new IllegalStateException("本机尚未注册云端设备");
            RemoteSignalingClient client = new RemoteSignalingClient();
            RemoteSignalingClient previous = signalingClient;
            signalingClient = client;
            if (previous != null) previous.close();

            client.connect(record.serverUrl, record.deviceToken, new RemoteSignalingClient.Listener() {
                @Override public void onConnected() {
                    if (!running.get() || generation != connectionGeneration.get()) return;
                    reconnectDelaySeconds = INITIAL_RECONNECT_SECONDS;
                    broadcastStatus("云端信令已连接，正在等待远程申请");
                }

                @Override public void onSessionRequested(String sessionId, String requesterId, String reason) {
                    if (!running.get() || generation != connectionGeneration.get()) return;
                    notifyOwner(sessionId, requesterId, reason);
                }

                @Override public void onSignal(String sessionId, org.json.JSONObject payload) {
                    // Presence owns approval requests only. Active screen sessions use their own socket.
                }

                @Override public void onDisconnected(String message) {
                    if (!running.get() || generation != connectionGeneration.get()) return;
                    Log.w(TAG, message);
                    broadcastStatus(message + "；将自动重连");
                    scheduleReconnect();
                }
            });
        } catch (Exception error) {
            Log.w(TAG, "Presence connection attempt failed", error);
            broadcastStatus("云端连接失败：" + safeMessage(error) + "；将自动重试");
            scheduleReconnect();
        }
    }

    private void sendHeartbeat() {
        if (!running.get()) return;
        try {
            RemoteDeviceStore.DeviceRecord record = RemoteDeviceStore.load(this);
            if (record == null) throw new IllegalStateException("设备凭证不存在");
            RemoteApiClient.heartbeat(record.serverUrl, record.deviceToken);
        } catch (Exception error) {
            Log.w(TAG, "Device heartbeat failed", error);
            broadcastStatus("在线心跳失败：" + safeMessage(error));
        }
    }

    private synchronized void scheduleReconnect() {
        if (!running.get() || scheduler == null || scheduler.isShutdown()) return;
        if (reconnectTask != null && !reconnectTask.isDone()) return;
        long delay = reconnectDelaySeconds;
        reconnectDelaySeconds = Math.min(MAX_RECONNECT_SECONDS, reconnectDelaySeconds * 2L);
        reconnectTask = scheduler.schedule(this::connectSignaling, delay, TimeUnit.SECONDS);
    }

    private void notifyOwner(String sessionId, String requesterId, String reason) {
        Intent tapIntent = new Intent(this, RemoteHelperActivity.class)
                .putExtra(EXTRA_PENDING_SESSION_ID, sessionId)
                .putExtra(EXTRA_PENDING_REQUESTER_ID, requesterId)
                .putExtra(EXTRA_PENDING_REASON, reason)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, sessionId.hashCode(), tapIntent, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("GamePilot：收到远程连接申请")
                .setContentText("申请账号：" + safeText(requesterId) + "，点击查看并决定")
                .setStyle(new Notification.BigTextStyle().bigText(
                        "申请账号：" + safeText(requesterId) + "\n申请原因：" + safeText(reason)
                                + "\n必须由设备主人在应用内明确批准。"))
                .setContentIntent(contentIntent)
                .setAutoCancel(true);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID + 1, builder.build());

        Intent event = new Intent(ACTION_SESSION_REQUESTED)
                .setPackage(getPackageName())
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_REQUESTER_ID, requesterId)
                .putExtra(EXTRA_REASON, reason);
        sendBroadcast(event);
    }

    private void startPresenceForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "GamePilot 设备在线服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("设备在线心跳和远程申请提醒；不会自行开启屏幕采集");
            manager.createNotificationChannel(channel);
        }

        Intent openIntent = new Intent(this, RemoteHelperActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openPending = PendingIntent.getActivity(this, 7312, openIntent, pendingFlags);

        Intent stopIntent = new Intent(this, RemotePresenceService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 7313, stopIntent, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("GamePilot 设备在线服务运行中")
                .setContentText("正在保持云端连接；屏幕不会被采集")
                .setContentIntent(openPending)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "停止在线服务", stopPending).build());
        startForeground(NOTIFICATION_ID, builder.build());
    }

    private void broadcastStatus(String message) {
        Intent status = new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_RUNNING, running.get())
                .putExtra(EXTRA_STATUS, message);
        sendBroadcast(status);
    }

    private void stopPresence() {
        if (!running.getAndSet(false)) {
            stopSelf();
            return;
        }
        connectionGeneration.incrementAndGet();
        if (heartbeatTask != null) heartbeatTask.cancel(true);
        if (reconnectTask != null) reconnectTask.cancel(true);
        RemoteSignalingClient client = signalingClient;
        signalingClient = null;
        if (client != null) client.close();
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current != null) current.shutdownNow();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID + 1);
        broadcastStatus("设备在线服务已停止");
        stopForeground(true);
        stopSelf();
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private String safeText(String value) {
        return value == null || value.trim().isEmpty() ? "未知" : value;
    }

    @Override
    public void onDestroy() {
        stopPresence();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
