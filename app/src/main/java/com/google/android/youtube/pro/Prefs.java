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
    /** Sleep fade length in seconds: 10 / 30 / 60 (13). */
    public static final String SLEEP_FADE = "sleepFade";
    /** Starting a timer makes it the default for the one-tap notification button (15). */
    public static final String SLEEP_REMEMBER = "sleepRemember";
    /** Show the sleep time left in the player corner (154). */
    public static final String SLEEP_BADGE = "sleepBadge";
    /** Night (22:00-05:00): offer a sleep timer when the app is opened (18). */
    public static final String NIGHT_PROMPT = "nightPrompt";
    public static final String NIGHT_SHOWN = "nightShown";
    /** What plays next: rec / channel / playlist (22); calm rule while sleeping (21). */
    public static final String NEXT_SOURCE = "nextSource";
    public static final String SLEEP_CALM = "sleepCalm";
    /** Notification shows -10 s / +30 s instead of previous / next (26). */
    public static final String NOTIF_SEEK = "notifSeek";
    /** Headset button: double press = next, triple = previous (24). */
    public static final String HEADSET_MULTI = "headsetMulti";
    /** Reopened after the system killed the app: offer to continue the last video (27). */
    public static final String RESUME_PROMPT = "resumePrompt";
    public static final String LAST_VIDEO = "lastVideo";
    public static final String LAST_POS = "lastPos";
    public static final String LAST_TITLE = "lastTitle";
    public static final String LAST_AT = "lastAt";
    /** Signed out: remember each video's position locally (28). */
    public static final String RESUME_LOCAL = "resumeLocal";
    /** Default quality: auto / wifiMax / 1080 / 720 / 480 (29). */
    public static final String QUALITY = "quality";
    /** Remember the playback speed (30). */
    public static final String REMEMBER_RATE = "rememberRate";
    public static final String RATE = "rate";
    /** Captions default: yt / off / zh (31). */
    public static final String CAPTIONS = "captions";
    /** Prefer the original audio track over auto-dubbing (32). */
    public static final String ORIG_AUDIO = "origAudio";
    /** No recommendation wall at the end of a video (36). */
    public static final String NO_END_WALL = "noEndWall";
    /** Volume levelling through WebAudio, experimental (20). */
    public static final String NORMALIZE = "normalize";
    /** Caption style: size default/s/l/xl, background default/half/none (155). */
    public static final String SUB_SIZE = "subSize";
    public static final String SUB_BG = "subBg";
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
    public static int sleepFade(Context c) {
        int v = get(c).getInt(SLEEP_FADE, 60);
        return v == 10 || v == 30 || v == 60 ? v : 60;
    }
    public static boolean sleepRemember(Context c) { return get(c).getBoolean(SLEEP_REMEMBER, true); }
    public static boolean bool(Context c, String key, boolean def) { return get(c).getBoolean(key, def); }
    public static String str(Context c, String key, String def) {
        String v = get(c).getString(key, def);
        return v == null ? def : v;
    }

    // defaults for every boolean setting, in one place (also used by "restore defaults")
    public static boolean def(String key) {
        switch (key) {
            case NORMALIZE: case REMEMBER_RATE: case SLEEP_CALM: case NOTIF_SEEK: return false;
            default: return true;
        }
    }
    public static boolean on(Context c, String key) { return get(c).getBoolean(key, def(key)); }

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
            o.put("fadeSec", sleepFade(c));
            o.put("nextSource", str(c, NEXT_SOURCE, "rec"));
            o.put("sleepCalm", on(c, SLEEP_CALM));
            o.put("resumeLocal", on(c, RESUME_LOCAL));
            o.put("quality", str(c, QUALITY, "auto"));
            o.put("rememberRate", on(c, REMEMBER_RATE));
            double rate = 1;
            try { rate = Double.parseDouble(str(c, RATE, "1")); } catch (Exception ignored) {}
            o.put("rate", rate);
            o.put("captions", str(c, CAPTIONS, "yt"));
            o.put("origAudio", on(c, ORIG_AUDIO));
            o.put("noEndWall", on(c, NO_END_WALL));
            o.put("normalize", on(c, NORMALIZE));
            o.put("sleepBadge", on(c, SLEEP_BADGE));
            o.put("subSize", str(c, SUB_SIZE, "default"));
            o.put("subBg", str(c, SUB_BG, "default"));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
