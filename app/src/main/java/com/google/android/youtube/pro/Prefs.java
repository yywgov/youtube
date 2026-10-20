package com.google.android.youtube.pro;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** All user settings live here (one SharedPreferences file). */
public final class Prefs {
    public static final String FILE = "ytlite";

    public static final String SPONSOR_BLOCK = "sponsorBlock";
    public static final String LOW_QUALITY_BG = "lowQualityBg";
    /** Home feed: hide videos already watched (>= 80 %). */
    public static final String HIDE_WATCHED = "hideWatched";
    /** Default sleep timer in minutes; -1 = end of current video. */
    public static final String SLEEP_DEFAULT = "sleepDefault";
    public static final String ASKED_BATTERY = "askedBattery";
    public static final String ASKED_NOTIF = "askedNotif";
    /** In-app updater: last automatic check (ms), version the user chose to skip, downloaded APK waiting for install. */
    public static final String LAST_UPDATE_CHECK = "lastUpdateCheck";
    public static final String SKIP_VERSION = "skipVersion";
    public static final String PENDING_APK = "pendingApk";

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean sponsorBlock(Context c) { return get(c).getBoolean(SPONSOR_BLOCK, true); }
    public static boolean lowQualityBg(Context c) { return get(c).getBoolean(LOW_QUALITY_BG, true); }
    public static boolean hideWatched(Context c) { return get(c).getBoolean(HIDE_WATCHED, true); }
    public static int sleepDefault(Context c) { return get(c).getInt(SLEEP_DEFAULT, 30); }

    public static void put(Context c, String key, boolean v) { get(c).edit().putBoolean(key, v).apply(); }
    public static void put(Context c, String key, int v) { get(c).edit().putInt(key, v).apply(); }
    public static void put(Context c, String key, long v) { get(c).edit().putLong(key, v).apply(); }
    public static void put(Context c, String key, String v) { get(c).edit().putString(key, v).apply(); }

    /** Config object handed to the injected script. */
    public static String jsConfig(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("sponsorBlock", sponsorBlock(c));
            o.put("lowQualityBg", lowQualityBg(c));
            o.put("hideWatched", hideWatched(c));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
