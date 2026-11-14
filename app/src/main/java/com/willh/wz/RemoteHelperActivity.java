package com.willh.wz;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * First enrollment slice of GamePilot Android Helper.
 * Owner sign-in is delegated to the configured identity provider; this screen accepts its JWT.
 */
public class RemoteHelperActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText serverInput;
    private EditText ownerTokenInput;
    private EditText deviceNameInput;
    private TextView statusView;
    private Button registerButton;
    private Button heartbeatButton;
    private Button listenButton;
    private boolean listening;
    private boolean presenceReceiverRegistered;
    private String shownPendingSessionId;
    private final BroadcastReceiver presenceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            if (RemotePresenceService.ACTION_STATUS.equals(intent.getAction())) {
                listening = intent.getBooleanExtra(RemotePresenceService.EXTRA_RUNNING, false);
                listenButton.setText(listening ? "断开云端连接" : "连接云端，等待远程申请");
                String message = intent.getStringExtra(RemotePresenceService.EXTRA_STATUS);
                if (message != null) setStatus(message);
            } else if (RemotePresenceService.ACTION_SESSION_REQUESTED.equals(intent.getAction())) {
                showApprovalDialog(
                        intent.getStringExtra(RemotePresenceService.EXTRA_SESSION_ID),
                        intent.getStringExtra(RemotePresenceService.EXTRA_REQUESTER_ID),
                        intent.getStringExtra(RemotePresenceService.EXTRA_REASON));
            }
        }
    };
    private static final int REQUEST_SCREEN_CAPTURE = 7401;
    private String approvedSessionId;
    private Button clearButton;
    private Button diagnosticsButton;
    private boolean deviceRegistered;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildView();
        showSavedDevice();
        handlePendingSessionIntent(getIntent());
    }

    private void buildView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("GamePilot 设备接入");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView description = new TextView(this);
        description.setText("将本机注册到 GamePilot 云端。注册凭证会使用 Android Keystore 加密保存。此页面不会启动屏幕采集或远程控制。");
        description.setTextSize(14);
        description.setPadding(0, dp(8), 0, dp(16));
        root.addView(description);

        serverInput = addField(root, "云端 HTTPS 地址", "https://remote.example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        deviceNameInput = addField(root, "设备名称", android.os.Build.MODEL, InputType.TYPE_CLASS_TEXT);
        ownerTokenInput = addField(root, "账号登录令牌（OIDC JWT）", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        registerButton = new Button(this);
        registerButton.setText("注册本设备");
        root.addView(registerButton);
        registerButton.setOnClickListener(v -> registerDevice());

        listenButton = new Button(this);
        listenButton.setText("连接云端，等待远程申请");
        listenButton.setEnabled(false);
        root.addView(listenButton);
        listenButton.setOnClickListener(v -> toggleSignaling());

        heartbeatButton = new Button(this);
        heartbeatButton.setText("发送在线心跳");
        heartbeatButton.setEnabled(false);
        root.addView(heartbeatButton);
        heartbeatButton.setOnClickListener(v -> sendHeartbeat());

        clearButton = new Button(this);
        clearButton.setText("解除本机云端绑定");
        root.addView(clearButton);
        clearButton.setOnClickListener(v -> clearDevice());

        diagnosticsButton = new Button(this);
        diagnosticsButton.setText("复制远程诊断日志");
        root.addView(diagnosticsButton);
        diagnosticsButton.setOnClickListener(v -> copyDiagnostics());

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setPadding(0, dp(18), 0, 0);
        root.addView(statusView);
        setContentView(scroll);
    }

    private EditText addField(LinearLayout root, String label, String value, int inputType) {
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(13);
        caption.setPadding(0, dp(10), 0, dp(4));
        root.addView(caption);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setInputType(inputType);
        root.addView(input);
        return input;
    }

    private void registerDevice() {
        final String server;
        try {
            server = RemoteApiClient.normalizeServerUrl(serverInput.getText().toString());
        } catch (Exception error) {
            setStatus(error.getMessage());
            return;
        }
        final String ownerJwt = ownerTokenInput.getText().toString().trim();
        final String deviceName = deviceNameInput.getText().toString().trim();
        if (ownerJwt.isEmpty()) {
            setStatus("请先登录账号，并粘贴身份服务签发的访问令牌。");
            return;
        }
        if (deviceName.isEmpty()) {
            setStatus("请填写设备名称。");
            return;
        }

        setBusy(true);
        executor.execute(() -> {
            try {
                RemoteApiClient.Registration registration = RemoteApiClient.register(
                        server, ownerJwt, deviceName, BuildConfig.VERSION_NAME);
                RemoteDeviceStore.save(this, server, registration.deviceId, registration.deviceToken);
                runOnUiThread(() -> {
                    ownerTokenInput.setText("");
                    deviceRegistered = true;
                    setBusy(false);
                    listenButton.setEnabled(true);
                    setStatus("设备注册成功\n设备 ID：" + registration.deviceId
                            + "\n凭证已加密保存。当前仅完成设备接入，尚未建立远程会话。");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("注册失败：" + safeMessage(error));
                });
            }
        });
    }


    private void toggleSignaling() {
        if (listening) {
            Intent stop = new Intent(this, RemotePresenceService.class)
                    .setAction(RemotePresenceService.ACTION_STOP);
            startService(stop);
            listening = false;
            listenButton.setText("连接云端，等待远程申请");
            setStatus("已请求停止设备在线服务。");
            return;
        }
        Intent start = new Intent(this, RemotePresenceService.class)
                .setAction(RemotePresenceService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(start);
        else startService(start);
        listening = true;
        listenButton.setText("断开云端连接");
        setStatus("设备在线服务正在启动。通知栏会显示运行状态；屏幕采集仍需每次单独批准和系统授权。");
    }

    private void showApprovalDialog(String sessionId, String requesterId, String reason) {
        if (sessionId == null || sessionId.isEmpty() || sessionId.equals(shownPendingSessionId)) return;
        shownPendingSessionId = sessionId;
        new AlertDialog.Builder(this)
                .setTitle("远程连接申请")
                .setMessage("申请账号：" + requesterId + "\n申请原因：" + reason + "\n\n是否允许本次连接？")
                .setCancelable(false)
                .setPositiveButton("允许本次连接", (dialog, which) -> decideSession(sessionId, true))
                .setNegativeButton("拒绝", (dialog, which) -> decideSession(sessionId, false))
                .setOnCancelListener(dialog -> shownPendingSessionId = null)
                .show();
    }

    private void decideSession(String sessionId, boolean approve) {
        shownPendingSessionId = null;
        executor.execute(() -> {
            try {
                RemoteDeviceStore.DeviceRecord record = RemoteDeviceStore.load(this);
                if (record == null) throw new IllegalStateException("设备凭证不存在");
                RemoteApiClient.decideSession(record.serverUrl, record.deviceToken, sessionId, approve);
                runOnUiThread(() -> {
                    if (approve) {
                        approvedSessionId = sessionId;
                        requestScreenCapturePermission();
                    } else {
                        setStatus("已拒绝本次远程连接申请。");
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> setStatus("提交审批失败：" + safeMessage(error)));
            }
        });
    }


    private void requestScreenCapturePermission() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        setStatus("远程会话已批准。接下来请在 Android 系统弹窗中确认屏幕共享。");
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SCREEN_CAPTURE) return;
        String sessionId = approvedSessionId;
        approvedSessionId = null;
        if (resultCode == RESULT_OK && data != null && sessionId != null) {
            Intent service = new Intent(this, RemoteCaptureService.class);
            service.setAction(RemoteCaptureService.ACTION_START);
            service.putExtra(RemoteCaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(RemoteCaptureService.EXTRA_RESULT_DATA, data);
            service.putExtra(RemoteCaptureService.EXTRA_SESSION_ID, sessionId);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
            setStatus("屏幕共享已启动，正在建立 WebRTC 连接。连接成功后桌面端即可观看画面；通知栏可随时停止。");
        } else if (sessionId != null) {
            setStatus("你取消了系统屏幕共享授权，正在结束本次会话。");
            executor.execute(() -> {
                try {
                    RemoteDeviceStore.DeviceRecord record = RemoteDeviceStore.load(this);
                    if (record != null) RemoteApiClient.endSession(record.serverUrl, record.deviceToken, sessionId);
                } catch (Exception ignored) {}
            });
        }
    }

    private void sendHeartbeat() {
        setBusy(true);
        executor.execute(() -> {
            try {
                RemoteDeviceStore.DeviceRecord record = RemoteDeviceStore.load(this);
                if (record == null) throw new IllegalStateException("本机尚未注册");
                RemoteApiClient.heartbeat(record.serverUrl, record.deviceToken);
                runOnUiThread(() -> {
                    setBusy(false);
                    setBusy(false);
                    setStatus("心跳成功：云端已确认设备在线。\n设备 ID：" + safeId());
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("心跳失败：" + safeMessage(error));
                });
            }
        });
    }

    private void showSavedDevice() {
        executor.execute(() -> {
            try {
                RemoteDeviceStore.DeviceRecord record = RemoteDeviceStore.load(this);
                runOnUiThread(() -> {
                    if (record == null) {
                        setStatus("尚未绑定云端设备。");
                        return;
                    }
                    serverInput.setText(record.serverUrl);
                    deviceRegistered = true;
                    heartbeatButton.setEnabled(true);
                    listenButton.setEnabled(true);
                    setStatus("已保存设备绑定\n设备 ID：" + record.deviceId
                            + "\n点击“发送在线心跳”验证服务器连接。");
                });
            } catch (Exception error) {
                runOnUiThread(() -> setStatus("读取本机凭证失败：" + safeMessage(error)));
            }
        });
    }

    private void copyDiagnostics() {
        String diagnostics = "GamePilot Android diagnostics\\n"
                + "App version: " + BuildConfig.VERSION_NAME + "\\n"
                + "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\\n\\n"
                + RemoteDiagnosticsStore.read(this);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("GamePilot diagnostics", diagnostics));
        Toast.makeText(this, "诊断日志已复制；内容不包含设备令牌或账号 JWT。", Toast.LENGTH_LONG).show();
    }

    private void clearDevice() {
        RemoteDeviceStore.clear(this);
        deviceRegistered = false;
        heartbeatButton.setEnabled(false);
        listenButton.setEnabled(false);
        startService(new Intent(this, RemotePresenceService.class).setAction(RemotePresenceService.ACTION_STOP));
        listening = false;
        listenButton.setText("连接云端，等待远程申请");
        setStatus("已清除本机保存的云端凭证。服务器端设备记录仍保留，需要由云端管理端另行撤销。");
    }

    private String safeId() {
        try {
            RemoteDeviceStore.DeviceRecord record = RemoteDeviceStore.load(this);
            return record == null ? "未知" : record.deviceId;
        } catch (Exception ignored) {
            return "未知";
        }
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private void setBusy(boolean busy) {
        registerButton.setEnabled(!busy);
        heartbeatButton.setEnabled(!busy && deviceRegistered);
        clearButton.setEnabled(!busy && deviceRegistered);
        listenButton.setEnabled(!busy && deviceRegistered);
    }

    private void setStatus(String text) {
        statusView.setText(text);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void handlePendingSessionIntent(Intent intent) {
        if (intent == null) return;
        String sessionId = intent.getStringExtra(RemotePresenceService.EXTRA_PENDING_SESSION_ID);
        if (sessionId == null) return;
        showApprovalDialog(
                sessionId,
                intent.getStringExtra(RemotePresenceService.EXTRA_PENDING_REQUESTER_ID),
                intent.getStringExtra(RemotePresenceService.EXTRA_PENDING_REASON));
        intent.removeExtra(RemotePresenceService.EXTRA_PENDING_SESSION_ID);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePendingSessionIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(RemotePresenceService.ACTION_STATUS);
        filter.addAction(RemotePresenceService.ACTION_SESSION_REQUESTED);
        registerReceiver(presenceReceiver, filter);
        presenceReceiverRegistered = true;
        startService(new Intent(this, RemotePresenceService.class)
                .setAction(RemotePresenceService.ACTION_QUERY));
    }

    @Override
    protected void onStop() {
        if (presenceReceiverRegistered) {
            unregisterReceiver(presenceReceiver);
            presenceReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
