package com.willh.wz;

import org.json.JSONObject;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Authenticated, ticket-based WSS listener for owner approval requests. */
public final class RemoteSignalingClient {
    public interface Listener {
        void onConnected();
        void onSessionRequested(String sessionId, String requesterId, String reason);
        void onSignal(String sessionId, JSONObject payload);
        void onDisconnected(String message);
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    private WebSocket socket;

    public void connect(String serverUrl, String deviceToken, Listener listener) throws Exception {
        String ticket = RemoteApiClient.createRealtimeTicket(serverUrl, deviceToken);
        HttpUrl base = HttpUrl.parse(RemoteApiClient.normalizeServerUrl(serverUrl));
        if (base == null) throw new IllegalArgumentException("无效服务器地址");
        HttpUrl wsUrl = base.newBuilder()
                .scheme("wss")
                .addPathSegments("v1/realtime")
                .addQueryParameter("ticket", ticket)
                .build();

        Request request = new Request.Builder().url(wsUrl).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                listener.onConnected();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject message = new JSONObject(text);
                    if ("session.requested".equals(message.optString("type"))) {
                        listener.onSessionRequested(
                                message.getString("sessionId"),
                                message.optString("requesterId", "未知申请账号"),
                                message.optString("reason", "远程用户请求连接")
                        );
                    } else if ("signal".equals(message.optString("type"))) {
                        JSONObject payload = message.optJSONObject("payload");
                        if (payload != null) listener.onSignal(message.getString("sessionId"), payload);
                    } else if ("session.ended".equals(message.optString("type"))) {
                        listener.onDisconnected("远程会话已结束");
                    } else if ("error".equals(message.optString("type"))) {
                        listener.onDisconnected("信令服务拒绝请求：" + message.optString("code", "UNKNOWN"));
                    }
                } catch (Exception ignored) {
                    listener.onDisconnected("收到无法识别的信令消息");
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                listener.onDisconnected("云端连接已关闭");
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                listener.onDisconnected("云端信令连接失败：" + (error.getMessage() == null ? "网络错误" : error.getMessage()));
            }
        });
    }

    public boolean sendSignal(String sessionId, JSONObject payload) {
        WebSocket current = socket;
        if (current == null) return false;
        JSONObject message = new JSONObject();
        try {
            message.put("type", "signal");
            message.put("sessionId", sessionId);
            message.put("payload", payload);
            return current.send(message.toString());
        } catch (Exception error) {
            return false;
        }
    }

    public boolean sendSessionStarted(String sessionId) {
        WebSocket current = socket;
        if (current == null) return false;
        JSONObject message = new JSONObject();
        try {
            message.put("type", "session.started");
            message.put("sessionId", sessionId);
            return current.send(message.toString());
        } catch (Exception error) {
            return false;
        }
    }

    public void close() {
        if (socket != null) {
            socket.close(1000, "owner closed helper screen");
            socket = null;
        }
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
