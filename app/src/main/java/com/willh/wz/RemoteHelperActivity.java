package com.willh.wz;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
    private Button clearButton;
    private boolean deviceRegistered;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildView();
        showSavedDevice();
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

        heartbeatButton = new Button(this);
        heartbeatButton.setText("发送在线心跳");
        heartbeatButton.setEnabled(false);
        root.addView(heartbeatButton);
        heartbeatButton.setOnClickListener(v -> sendHeartbeat());

        clearButton = new Button(this);
        clearButton.setText("解除本机云端绑定");
        root.addView(clearButton);
        clearButton.setOnClickListener(v -> clearDevice());

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
                    setStatus("已保存设备绑定\n设备 ID：" + record.deviceId
                            + "\n点击“发送在线心跳”验证服务器连接。");
                });
            } catch (Exception error) {
                runOnUiThread(() -> setStatus("读取本机凭证失败：" + safeMessage(error)));
            }
        });
    }

    private void clearDevice() {
        RemoteDeviceStore.clear(this);
        deviceRegistered = false;
        heartbeatButton.setEnabled(false);
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
    }

    private void setStatus(String text) {
        statusView.setText(text);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
