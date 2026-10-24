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
    // ---- batch 5 (v1.7.0): new keys only, every older key keeps its name / type / meaning ----
    public static final String WIFI_SAVER = "wifiSaver";             // screen off: no high-perf Wi-Fi lock (271), off by default
    public static final String IDLE_GUARD = "idleGuard";             // 3 h without input: 30 min timer (277)
    public static final String NIGHT_START = "nightStart";           // night window start hour (307)
    public static final String NIGHT_END = "nightEnd";               // night window end hour (307)
    public static final String SKIP_WATCHED_NEXT = "skipWatchedNext"; // recommended: skip watched autoplay targets (311)
    public static final String FONT_LARGE = "fontLarge";             // text zoom up to 160 % (378)
    public static final String ORIENT_REMEMBER = "orientRemember";   // keep the orientation lock across starts (388)
    public static final String ORIENT_LOCK = "orientLock";           // 0 follow system, 1 portrait, 2 landscape (388)
    // ---- batch 6 (v1.8.0): new keys only, every older key keeps its name / type / meaning ----
    public static final String HIDE_WATCHED_RELATED = "hideWatchedRelated"; // related videos also hide watched ones (327)
    public static final String HIDE_SEARCH_MIX = "hideSearchMix";    // search: no auto-generated mixes (333)
    public static final String LIGHT_REFRESH = "lightRefresh";       // refresh keeps service worker / static caches (316)
    public static final String EXTEND_STEP = "extendMin";            // "+N min" step: 10 / 15 / 30 (303)
    public static final String SLEEP_HOLD = "sleepPauseHold";        // paused playback holds a minutes timer (305)
    public static final String SLEEP_MODE_BLACK = "sleepModeBlack";  // one-tap sleep mode: black screen after 10 s (306)
    public static final String LIST_END_NEXT = "listEndNext";        // playlist finished: go on with a recommendation (313)
    public static final String SLEEP_STRICT = "sleepStrict";         // sleeping, same channel only: stop when none (315)
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
    // batch 4 (v1.6.0) state – never part of a backup
    /** Running sleep timer "mode|endWallMs|countLeft|savedAtMs" so a restarted process keeps it (244). */
    public static final String SLEEP_STATE = "sleepState";
    /** Wall time the last foreground-service start was refused (291); 0 = fine. */
    public static final String FGS_DENIED_AT = "fgsDeniedAt";
    // batch 5 (v1.7.0) state – never part of a backup
    /** Version for which the "ad blocking component failed" bar was shown (254). */
    public static final String SELFCHECK_WARNED = "selfCheckWarned";
    /** Last update check: "MM-dd HH:mm · result" (398). */
    public static final String UPDATE_LAST_RESULT = "updateLastResult";
    /** Sign-in was refused as "browser not secure": send Chrome-like client hints from now on (344). */
    public static final String CH_UA_FIX = "chUaFix";
    // batch 6 (v1.8.0) state – never part of a backup
    /** Sleep nights for the about page (276): {"cur":[startMs,battery%],"log":[[atMs,minutes,drop%],…]}. */
    public static final String SLEEP_BATTERY = "sleepBattery";

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean sponsorBlock(Context c) { return get(c).getBoolean(SPONSOR_BLOCK, true); }
    public static boolean lowQualityBg(Context c) { return get(c).getBoolean(LOW_QUALITY_BG, true); }
    public static boolean hideWatched(Context c) { return get(c).getBoolean(HIDE_WATCHED, true); }
    public static boolean perfCss(Context c) { return get(c).getBoolean(PERF_CSS, true); }

    /** All SponsorBlock categories offered in settings, with their labels. */
    public static final String[] SB_CATS = PrefsModel.SB_CATS;
    public static final String[] SB_LABELS = {"赞助", "自我推广", "求订阅/互动", "片头", "片尾", "预告/回顾", "非音乐部分", "离题闲聊"};

    public static java.util.List<String> sponsorCats(Context c) {
        // SB_CATS here and in PrefsModel are the same list (contract test)
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
    public static int num(Context c, String key) { return get(c).getInt(key, PrefsModel.defInt(key)); }
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
    public static boolean def(String key) { return PrefsModel.defBool(key); }
    public static boolean on(Context c, String key) { return get(c).getBoolean(key, def(key)); }

    /** Every user setting (not state): backup / restore (98) and "restore defaults" (136); the lists live in {@link PrefsModel}. */
    public static final String[] BOOL_KEYS = PrefsModel.BOOL_KEYS;
    public static final String[] INT_KEYS = PrefsModel.INT_KEYS;
    public static final String[] STR_KEYS = PrefsModel.STR_KEYS;

    /** SharedPreferences seen through the model's store interface; writes go out with one apply(). */
    static final class PrefStore implements PrefsModel.Store {
        private final java.util.Map<String, ?> all;
        private final SharedPreferences.Editor e;
        private final java.util.Map<String, Object> pending = new java.util.HashMap<>();
        private final java.util.Set<String> removed = new java.util.HashSet<>();
        PrefStore(SharedPreferences p) { all = p.getAll(); e = p.edit(); }
        @Override public Object get(String k) {
            if (removed.contains(k)) return null;
            if (pending.containsKey(k)) return pending.get(k);
            return all.get(k);
        }
        @Override public void put(String k, Object v) {
            removed.remove(k);
            pending.put(k, v);
            if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Long) e.putLong(k, (Long) v);
            else if (v instanceof Number) e.putInt(k, ((Number) v).intValue());
            else if (v != null) e.putString(k, String.valueOf(v));
        }
        @Override public void remove(String k) { pending.remove(k); removed.add(k); e.remove(k); }
        void apply() { e.apply(); }
    }

    /**
     * Settings schema migration (226): first thing in MainActivity.onCreate. Idempotent; a failure is
     * only logged and never blocks the start. Key names / types / meanings are never changed by it.
     */
    public static void migrate(Context c) {
        try {
            PrefStore s = new PrefStore(get(c));
            if (PrefsModel.migrate(s)) {
                s.apply();
                Diag.log("prefs", "schema -> " + PrefsModel.SCHEMA);
            }
        } catch (Throwable t) {
            try { Diag.log("prefs", "migrate failed: " + t); } catch (Throwable ignored) {}
        }
    }

    /** Backup v2 (241): {"app":"YiWei","v":2,"schema":2,"exportedAt":…,"appVersion":…, keys…}. */
    public static String exportJson(Context c) { return exportJson(c, null); }

    public static String exportJson(Context c, String appVersion) {
        try {
            JSONObject o = new JSONObject();
            o.put("app", "YiWei");   // ASCII name: v1.5.1 and older only accept "YiWei" / "YT Lite"
            o.put("v", 2);
            o.put("schema", PrefsModel.SCHEMA);
            o.put("exportedAt", System.currentTimeMillis());
            if (appVersion != null) o.put("appVersion", appVersion);
            for (java.util.Map.Entry<String, Object> en : PrefsModel.exportMap(new PrefStore(get(c))).entrySet()) o.put(en.getKey(), en.getValue());
            return o.toString(1);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static java.util.Map<String, Object> toMap(JSONObject o) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            if (v != null && v != JSONObject.NULL) m.put(k, v);
        }
        return m;
    }

    /**
     * Import a backup (v1 or v2); every value is checked against the options this version offers,
     * the rest is skipped and counted. Returns null when the text is not a backup of this app.
     */
    public static PrefsModel.ImportResult importJson(Context c, String json) { return importJson(c, json, false); }

    public static PrefsModel.ImportResult importJson(Context c, String json, boolean replace) {
        try {
            JSONObject o = new JSONObject(json.trim());
            PrefStore s = new PrefStore(get(c));
            PrefsModel.ImportResult r = PrefsModel.importMap(toMap(o), s, replace);
            if (r.notBackup) return null;
            s.apply();
            return r;
        } catch (Exception ex) {
            return null;
        }
    }

    /** "Restore defaults" (136): block lists are kept (240), see {@link #clearBlockLists}. */
    public static void restoreDefaults(Context c) {
        PrefStore s = new PrefStore(get(c));
        PrefsModel.restoreDefaults(s);
        s.apply();
    }

    public static void clearBlockLists(Context c) {
        get(c).edit().remove(BLOCK_CHANNELS).remove(BLOCK_WORDS).apply();
    }

    /** Snapshot before "restore defaults" / import, kept for undo (406). */
    public static void saveSnapshot(Context c) {
        put(c, PrefsModel.SNAPSHOT, exportJson(c));
    }

    /** Put the settings back exactly as in the last snapshot. False when there is none. */
    public static boolean undoSnapshot(Context c) {
        String snap = str(c, PrefsModel.SNAPSHOT, "");
        if (snap.isEmpty()) return false;
        PrefsModel.ImportResult r = importJson(c, snap, true);
        if (r == null) return false;
        get(c).edit().remove(PrefsModel.SNAPSHOT).apply();
        return true;
    }

    static java.util.List<String> lines(Context c, String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : str(c, key, "").split("\\n")) {
            s = s.trim();
            // a "re:" pattern may be up to 100 characters (323); everything else is cut at 80
            int max = s.regionMatches(true, 0, "re:", 0, 3) ? 103 : 80;
            if (!s.isEmpty() && !out.contains(s) && out.size() < 300) out.add(s.length() > max ? s.substring(0, max) : s);
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

    /** System "remove animations" (animator duration scale 0, 367). */
    public static boolean animationsOff(Context c) {
        try {
            return android.provider.Settings.Global.getFloat(c.getContentResolver(), android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Display name (一维视频), without a compile-time dependency on R (JVM tests build this file alone). */
    public static String appName(Context c) {
        try {
            int res = c.getApplicationInfo().labelRes;
            if (res != 0) return c.getString(res);
        } catch (Throwable ignored) {}
        return "一维视频";
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
            // batch 5 (v1.7.0)
            o.put("lowMotion", animationsOff(c));
            o.put("fontLarge", on(c, FONT_LARGE) && on(c, FONT_FOLLOW));
            o.put("skipWatchedNext", on(c, SKIP_WATCHED_NEXT));
            o.put("appName", appName(c));
            // batch 6 (v1.8.0)
            o.put("hideWatchedRelated", on(c, HIDE_WATCHED_RELATED));
            o.put("hideSearchMix", on(c, HIDE_SEARCH_MIX));
            o.put("lightRefresh", on(c, LIGHT_REFRESH));
            o.put("listEndNext", on(c, LIST_END_NEXT));
            o.put("sleepStrict", on(c, SLEEP_STRICT));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
