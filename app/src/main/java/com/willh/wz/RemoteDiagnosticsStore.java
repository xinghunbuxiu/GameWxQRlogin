package com.willh.wz;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small, token-free diagnostic ring buffer for field testing. */
public final class RemoteDiagnosticsStore {
    private static final String PREFS = "gamepilot_diagnostics";
    private static final String KEY_EVENTS = "events";
    private static final int MAX_LINES = 80;

    private RemoteDiagnosticsStore() {}

    public static synchronized void append(Context context, String event) {
        String safe = event == null ? "unknown" : event.replace('\n', ' ').replace('\r', ' ');
        if (safe.length() > 240) safe = safe.substring(0, 240);
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
        String line = timestamp + " | " + safe;
        String existing = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_EVENTS, "");
        String[] lines = existing.isEmpty() ? new String[0] : existing.split("\\n");
        StringBuilder output = new StringBuilder();
        int start = Math.max(0, lines.length - MAX_LINES + 1);
        for (int i = start; i < lines.length; i++) {
            if (output.length() > 0) output.append('\n');
            output.append(lines[i]);
        }
        if (output.length() > 0) output.append('\n');
        output.append(line);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_EVENTS, output.toString()).apply();
    }

    public static String read(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_EVENTS, "暂无诊断记录");
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_EVENTS).apply();
    }
}
