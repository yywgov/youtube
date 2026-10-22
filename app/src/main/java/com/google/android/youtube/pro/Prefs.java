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
    /** SponsorBlock categories to skip, comma separated (default: sponsor only). */
    public static final String SPONSOR_CATS = "sponsorCats";
    /** Smoothness CSS: off-screen cards skip rendering, no skeleton/ripple animations. */
    public static final String PERF_CSS = "perfCss";
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
    public static boolean perfCss(Context c) { return get(c).getBoolean(PERF_CSS, true); }

    /** All SponsorBlock categories offered in settings, with their labels. */
    public static final String[] SB_CATS = {"sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic", "filler"};
    public static final String[] SB_LABELS = {"赞助", "自我推广", "求订阅/互动", "片头", "片尾", "预告/回顾", "非音乐部分", "离题闲聊"};

    public static java.util.List<String> sponsorCats(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String v = get(c).getString(SPONSOR_CATS, "sponsor");
        if (v == null) v = "sponsor";
        for (String s : v.split(",")) {
            s = s.trim();
            for (String k : SB_CATS) if (k.equals(s) && !out.contains(s)) out.add(s);
        }
        return out;
    }

    public static String sponsorCatsLabel(Context c) {
        java.util.List<String> l = sponsorCats(c);
        if (l.isEmpty()) return "未选择";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < SB_CATS.length; i++) {
            if (!l.contains(SB_CATS[i])) continue;
            if (b.length() > 0) b.append("、");
            b.append(SB_LABELS[i]);
        }
        return b.toString();
    }

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
            o.put("perfCss", perfCss(c));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
