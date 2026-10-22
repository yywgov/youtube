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
    // ---- batch 3 (v1.4.0) ----
    public static final String SEEN_MIN = "seenMin";                 // 50 / 80 / 95 (62)
    public static final String BLOCK_CHANNELS = "blockChannels";     // one per line (56)
    public static final String BLOCK_WORDS = "blockWords";           // one per line (57)
    public static final String MIN_DUR = "minDur";                   // minutes, 0 = off (58)
    public static final String MAX_AGE = "maxAgeDays";               // days, 0 = off (59)
    public static final String HIDE_LIVE = "hideLive";               // (60)
    public static final String HIDE_SHELVES = "hideShelves";         // (61)
    public static final String START_PAGE = "startPage";             // home / subs (63)
    public static final String SUBS_ONLY_VIDEOS = "subsOnlyVideos";  // (63)
    public static final String REFRESH_COUNT = "refreshCount";       // (64)
    public static final String HIDE_SEARCH_EXTRAS = "hideSearchExtras"; // (70)
    public static final String REMOTE_RULES = "remoteRules";         // (73)
    public static final String UPDATE_MIRROR = "updateMirror";       // (87)
    public static final String AMOLED = "amoled";                    // (91)
    public static final String FONT_FOLLOW = "fontFollow";           // (93)
    public static final String COMPACT = "compact";                  // (108)
    public static final String CARD_STYLE = "cardStyle";             // (106/107/111)
    public static final String DIM_THUMBS = "dimThumbs";             // (110)
    public static final String ACCENT = "accent";                    // "" or #RRGGBB (103/104)
    public static final String LONG_PRESS = "longPress";             // (123)
    public static final String SEARCH_FOCUS = "searchFocus";         // (124)
    public static final String SEARCH_CHIPS = "searchChips";         // (125)
    public static final String KEEP_SCROLL = "keepScroll";           // (119)
    public static final String HAPTIC = "haptic";                    // (120)
    public static final String POWER_SAVE_AUTO = "powerSaveAuto";    // (147)
    public static final String NOTIF_WL = "notifWatchLater";         // (100)
    // state (not settings)
    public static final String UPDATE_READY = "updateReady";         // tag of a newer release found by the daily check (137)
    public static final String LAST_RUN_VERSION = "lastRunVersion";  // (138)
    public static final String NOTES_FOR = "notesFor";
    public static final String NOTES = "notes";
    public static final String WATCH_STATS = "watchStats";           // {"yyyymmdd": seconds} (159)
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
            case NORMALIZE: case REMEMBER_RATE: case SLEEP_CALM: case NOTIF_SEEK:
            case HIDE_LIVE: case HIDE_SHELVES: case SUBS_ONLY_VIDEOS: case UPDATE_MIRROR: case AMOLED:
            case COMPACT: case CARD_STYLE: case DIM_THUMBS:
                return false;
            default: return true;
        }
    }
    public static boolean on(Context c, String key) { return get(c).getBoolean(key, def(key)); }

    /** Every user setting (not state): backup / restore (98) and "restore defaults" (136). */
    public static final String[] BOOL_KEYS = {SPONSOR_BLOCK, LOW_QUALITY_BG, HIDE_WATCHED, PERF_CSS, SLEEP_REMEMBER, SLEEP_BADGE,
            NIGHT_PROMPT, SLEEP_CALM, NOTIF_SEEK, HEADSET_MULTI, RESUME_PROMPT, RESUME_LOCAL, REMEMBER_RATE, ORIG_AUDIO,
            NO_END_WALL, NORMALIZE, HIDE_LIVE, HIDE_SHELVES, SUBS_ONLY_VIDEOS, REFRESH_COUNT, HIDE_SEARCH_EXTRAS, REMOTE_RULES,
            UPDATE_MIRROR, AMOLED, FONT_FOLLOW, COMPACT, CARD_STYLE, DIM_THUMBS, LONG_PRESS, SEARCH_FOCUS, SEARCH_CHIPS,
            KEEP_SCROLL, HAPTIC, POWER_SAVE_AUTO, NOTIF_WL};
    public static final String[] INT_KEYS = {SLEEP_DEFAULT, SLEEP_FADE, SEEN_MIN, MIN_DUR, MAX_AGE};
    public static final String[] STR_KEYS = {SPONSOR_CATS, NEXT_SOURCE, QUALITY, RATE, CAPTIONS, SUB_SIZE, SUB_BG,
            BLOCK_CHANNELS, BLOCK_WORDS, START_PAGE, ACCENT};

    public static String exportJson(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("app", "YiWei");
            o.put("v", 1);
            SharedPreferences p = get(c);
            for (String k : BOOL_KEYS) if (p.contains(k)) o.put(k, p.getBoolean(k, def(k)));
            for (String k : INT_KEYS) if (p.contains(k)) o.put(k, p.getInt(k, 0));
            for (String k : STR_KEYS) if (p.contains(k)) o.put(k, p.getString(k, ""));
            return o.toString(1);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Import a backup; only known keys with the right type are taken. Returns how many were applied, -1 if not a backup. */
    public static int importJson(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json.trim());
            if (!"YiWei".equals(o.optString("app")) && !"YT Lite".equals(o.optString("app"))) return -1;
            SharedPreferences.Editor e = get(c).edit();
            int n = 0;
            for (String k : BOOL_KEYS) if (o.has(k) && o.get(k) instanceof Boolean) { e.putBoolean(k, o.getBoolean(k)); n++; }
            for (String k : INT_KEYS) if (o.has(k) && o.get(k) instanceof Number) { e.putInt(k, o.getInt(k)); n++; }
            for (String k : STR_KEYS) if (o.has(k) && o.get(k) instanceof String && o.getString(k).length() < 4000) { e.putString(k, o.getString(k)); n++; }
            e.apply();
            return n;
        } catch (Exception ex) {
            return -1;
        }
    }

    public static void restoreDefaults(Context c) {
        SharedPreferences.Editor e = get(c).edit();
        for (String k : BOOL_KEYS) e.remove(k);
        for (String k : INT_KEYS) e.remove(k);
        for (String k : STR_KEYS) e.remove(k);
        e.apply();
    }

    static java.util.List<String> lines(Context c, String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : str(c, key, "").split("\\n")) {
            s = s.trim();
            if (!s.isEmpty() && !out.contains(s) && out.size() < 300) out.add(s.length() > 80 ? s.substring(0, 80) : s);
        }
        return out;
    }

    public static void addLine(Context c, String key, String v) {
        java.util.List<String> l = lines(c, key);
        v = v == null ? "" : v.trim();
        if (v.isEmpty() || l.contains(v)) return;
        l.add(0, v);
        StringBuilder b = new StringBuilder();
        for (String s : l) { if (b.length() > 0) b.append('\n'); b.append(s); }
        put(c, key, b.toString());
    }

    private static org.json.JSONArray arr(java.util.List<String> l) {
        org.json.JSONArray a = new org.json.JSONArray();
        for (String s : l) a.put(s);
        return a;
    }

    /** Battery saver currently on (147). */
    public static boolean powerSaving(Context c) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getApplicationContext().getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isPowerSaveMode();
        } catch (Exception e) {
            return false;
        }
    }

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
            o.put("seenMin", get(c).getInt(SEEN_MIN, 80));
            o.put("blockChannels", arr(lines(c, BLOCK_CHANNELS)));
            o.put("blockWords", arr(lines(c, BLOCK_WORDS)));
            o.put("minDur", get(c).getInt(MIN_DUR, 0));
            o.put("maxAgeDays", get(c).getInt(MAX_AGE, 0));
            o.put("hideLive", on(c, HIDE_LIVE));
            o.put("hideShelves", on(c, HIDE_SHELVES));
            o.put("subsOnlyVideos", on(c, SUBS_ONLY_VIDEOS));
            o.put("refreshCount", on(c, REFRESH_COUNT));
            o.put("hideSearchExtras", on(c, HIDE_SEARCH_EXTRAS));
            o.put("amoled", on(c, AMOLED));
            o.put("compact", on(c, COMPACT));
            o.put("cardStyle", on(c, CARD_STYLE));
            o.put("dimThumbs", on(c, DIM_THUMBS));
            o.put("accent", str(c, ACCENT, ""));
            o.put("longPress", on(c, LONG_PRESS));
            o.put("searchFocus", on(c, SEARCH_FOCUS));
            o.put("searchChips", on(c, SEARCH_CHIPS));
            o.put("keepScroll", on(c, KEEP_SCROLL));
            o.put("powerSave", on(c, POWER_SAVE_AUTO) && powerSaving(c));
            o.put("updateDot", !str(c, UPDATE_READY, "").isEmpty());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
