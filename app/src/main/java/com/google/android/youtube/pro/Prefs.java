package com.google.android.youtube.pro;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** All user settings live here (one SharedPreferences file). */
public final class Prefs {
    public static final String FILE = "ytlite";

    public static final String HIDE_SHORTS = "hideShorts";
    public static final String SPONSOR_BLOCK = "sponsorBlock";
    public static final String LOW_QUALITY_BG = "lowQualityBg";
    /** Default sleep timer in minutes; -1 = end of current video. */
    public static final String SLEEP_DEFAULT = "sleepDefault";
    public static final String ASKED_BATTERY = "askedBattery";
    public static final String ASKED_NOTIF = "askedNotif";

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean hideShorts(Context c) { return get(c).getBoolean(HIDE_SHORTS, false); }
    public static boolean sponsorBlock(Context c) { return get(c).getBoolean(SPONSOR_BLOCK, true); }
    public static boolean lowQualityBg(Context c) { return get(c).getBoolean(LOW_QUALITY_BG, true); }
    public static int sleepDefault(Context c) { return get(c).getInt(SLEEP_DEFAULT, 30); }

    public static void put(Context c, String key, boolean v) { get(c).edit().putBoolean(key, v).apply(); }
    public static void put(Context c, String key, int v) { get(c).edit().putInt(key, v).apply(); }

    /** Config object handed to the injected script. */
    public static String jsConfig(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("hideShorts", hideShorts(c));
            o.put("sponsorBlock", sponsorBlock(c));
            o.put("lowQualityBg", lowQualityBg(c));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
