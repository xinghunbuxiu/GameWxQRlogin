package com.willh.wz;

import org.json.JSONObject;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** HTTPS-only API client for the GamePilot remote control plane. */
public final class RemoteApiClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    private RemoteApiClient() {}

    public static final class Registration {
        public final String deviceId;
        public final String deviceToken;

        Registration(String deviceId, String deviceToken) {
            this.deviceId = deviceId;
            this.deviceToken = deviceToken;
        }
    }

    public static Registration register(String serverUrl, String ownerJwt, String displayName, String appVersion) throws Exception {
        JSONObject body = new JSONObject();
        body.put("displayName", displayName);
        body.put("platform", "android");
        body.put("appVersion", appVersion);
        body.put("capabilities", new org.json.JSONArray()
                .put("device.heartbeat")
                .put("session.owner_approval")
                .put("screen.capture.media_projection"));

        JSONObject result = post(serverUrl, "/v1/devices/register", ownerJwt, body);
        return new Registration(result.getString("deviceId"), result.getString("deviceToken"));
    }

    public static void heartbeat(String serverUrl, String deviceToken) throws Exception {
        JSONObject body = new JSONObject();
        body.put("status", "online");
        post(serverUrl, "/v1/devices/heartbeat", deviceToken, body);
    }

    private static JSONObject post(String serverUrl, String path, String bearer, JSONObject body) throws Exception {
        String base = normalizeServerUrl(serverUrl);
        Request request = new Request.Builder()
                .url(base + path)
                .header("Authorization", "Bearer " + bearer)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            String payload = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                String code = "HTTP_" + response.code();
                try {
                    JSONObject error = new JSONObject(payload);
                    if (error.has("error")) code = error.getString("error");
                } catch (Exception ignored) {}
                throw new IllegalStateException("服务器请求失败：" + code);
            }
            return payload.isEmpty() ? new JSONObject() : new JSONObject(payload);
        }
    }

    public static String normalizeServerUrl(String value) throws Exception {
        String candidate = value == null ? "" : value.trim().replaceAll("/+$", "");
        URI uri = new URI(candidate);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("服务器地址必须是有效的 HTTPS 地址");
        }
        return candidate;
    }
}
