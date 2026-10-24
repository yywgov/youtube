package com.google.android.youtube.pro;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Battery used by a night with the sleep timer (276): the charge when a timer starts and when it ends,
 * the last 7 nights kept on the phone only. Nights spent on the charger, or shorter than 10 minutes,
 * are not recorded.
 */
final class NightLog {
    private static final int KEEP = 7;
    private static final long MIN_MS = 10 * 60_000L;

    private NightLog() {}

    static void start(Context c) {
        if (c == null) return;
        try {
            JSONObject o = read(c);
            int b = battery(c);
            if (b < 0 || charging(c)) o.remove("cur");
            else o.put("cur", new JSONArray().put(System.currentTimeMillis()).put(b));
            Prefs.put(c, Prefs.SLEEP_BATTERY, o.toString());
        } catch (Throwable ignored) {}
    }

    static void end(Context c, boolean fired) {
        if (c == null) return;
        try {
            JSONObject o = read(c);
            JSONArray cur = o.optJSONArray("cur");
            if (cur == null) return;
            o.remove("cur");
            long t0 = cur.optLong(0, 0);
            int b0 = cur.optInt(1, -1), b1 = battery(c);
            long now = System.currentTimeMillis();
            if (t0 > 0 && b0 >= 0 && b1 >= 0 && !charging(c) && now - t0 >= MIN_MS && b1 <= b0) {
                JSONArray log = o.optJSONArray("log");
                if (log == null) log = new JSONArray();
                log.put(new JSONArray().put(now).put((now - t0) / 60_000L).put(b0 - b1));
                JSONArray keep = new JSONArray();
                for (int i = Math.max(0, log.length() - KEEP); i < log.length(); i++) keep.put(log.get(i));
                o.put("log", keep);
                Diag.log("night", "timer " + (fired ? "ended" : "cancelled") + ": " + Logic.nightSummary((now - t0) / 60_000L, b0 - b1));
            }
            Prefs.put(c, Prefs.SLEEP_BATTERY, o.toString());
        } catch (Throwable ignored) {}
    }

    /** "最近一晚（10-24）：播放 3 小时 10 分，耗电 9%", or "" when there is none yet. */
    static String last(Context c) {
        try {
            JSONArray log = read(c).optJSONArray("log");
            if (log == null || log.length() == 0) return "";
            JSONArray e = log.getJSONArray(log.length() - 1);
            String day = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.US).format(new java.util.Date(e.optLong(0)));
            return "最近一晚（" + day + "）：" + Logic.nightSummary(e.optLong(1), e.optInt(2)) + " · 共记 " + log.length() + " 晚";
        } catch (Throwable t) {
            return "";
        }
    }

    private static JSONObject read(Context c) {
        try { return new JSONObject(Prefs.str(c, Prefs.SLEEP_BATTERY, "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private static int battery(Context c) {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            int v = bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return v >= 0 && v <= 100 ? v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean charging(Context c) {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            return bm != null && bm.isCharging();
        } catch (Throwable t) {
            return false;
        }
    }
}
