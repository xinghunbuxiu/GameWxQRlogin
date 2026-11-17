package com.willh.wz;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the device bearer credential encrypted with a non-exportable Android Keystore key.
 * Remote enrollment is intentionally unavailable on Android versions below API 23.
 */
public final class RemoteDeviceStore {
    private static final String PREFS = "gamepilot_remote_device";
    private static final String KEY_ALIAS = "gamepilot.remote.device.v1";
    private static final String VALUE = "encrypted_device_record";

    private RemoteDeviceStore() {}

    public static final class DeviceRecord {
        public final String serverUrl;
        public final String deviceId;
        public final String deviceToken;

        DeviceRecord(String serverUrl, String deviceId, String deviceToken) {
            this.serverUrl = serverUrl;
            this.deviceId = deviceId;
            this.deviceToken = deviceToken;
        }
    }

    public static void save(Context context, String serverUrl, String deviceId, String deviceToken) throws Exception {
        requireSupportedVersion();
        JSONObject record = new JSONObject();
        record.put("serverUrl", serverUrl);
        record.put("deviceId", deviceId);
        record.put("deviceToken", deviceToken);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(record.toString().getBytes(StandardCharsets.UTF_8));

        JSONObject envelope = new JSONObject();
        envelope.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        envelope.put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        boolean stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(VALUE, envelope.toString()).commit();
        if (!stored) throw new IllegalStateException("无法保存设备凭证");
    }

    public static DeviceRecord load(Context context) throws Exception {
        requireSupportedVersion();
        String envelopeText = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(VALUE, null);
        if (envelopeText == null) return null;

        JSONObject envelope = new JSONObject(envelopeText);
        byte[] iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP);
        byte[] encrypted = Base64.decode(envelope.getString("data"), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        JSONObject record = new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
        return new DeviceRecord(
                record.getString("serverUrl"),
                record.getString("deviceId"),
                record.getString("deviceToken")
        );
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(VALUE).apply();
    }

    private static void requireSupportedVersion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw new IllegalStateException("GamePilot 设备凭证安全存储要求 Android 6.0 或更高版本");
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
