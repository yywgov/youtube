package com.google.android.youtube.pro;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plain-Java settings model (no Android classes, so it runs in JVM unit tests, 417):
 * the list of known setting keys, defaults, the schema migration (226) and the read/write
 * mappings used where one settings row edits two keys (229). Backup / restore and "restore
 * defaults" were removed in v1.15.
 *
 * Settings merge rule (v1.6): key names, types and meanings never change; a merged row is
 * only a view over the same keys, so an upgrade keeps every value as it was.
 */
public final class PrefsModel {
    private PrefsModel() {}

    /** Settings schema written by this version (state). */
    public static final int SCHEMA = 3;
    public static final String PREFS_SCHEMA = "prefsSchema";
    /** Settings JSON the removed undo of "restore defaults" / import kept (406); deleted by schema 3. */
    public static final String SNAPSHOT = "lastSnapshot";
    /** State of features removed in v1.15, deleted once by schema 3 (settings keys are never deleted). */
    public static final String[] REMOVED_STATE = {SNAPSHOT, Prefs.SLEEP_STATE, Prefs.NIGHT_SHOWN, Prefs.SLEEP_BATTERY, Prefs.MIRROR_OK};
    /** Pseudo key the v1.4–v1.5 layout row used in code; never meant to be stored (236). */
    public static final String OLD_COMPACT_CHOICE = Prefs.COMPACT + "_choice";

    /**
     * Every setting key this app has ever had (not state). Backup / restore and "restore defaults" were
     * removed in v1.15; the lists stay so the problem report shows them and old values remain known keys.
     */
    public static final String[] BOOL_KEYS = {Prefs.SPONSOR_BLOCK, Prefs.LOW_QUALITY_BG, Prefs.HIDE_WATCHED, Prefs.PERF_CSS,
            Prefs.SLEEP_REMEMBER, Prefs.SLEEP_BADGE, Prefs.NIGHT_PROMPT, Prefs.SLEEP_CALM, Prefs.NOTIF_SEEK, Prefs.HEADSET_MULTI,
            Prefs.RESUME_PROMPT, Prefs.RESUME_LOCAL, Prefs.REMEMBER_RATE, Prefs.ORIG_AUDIO, Prefs.NO_END_WALL, Prefs.NORMALIZE,
            Prefs.HIDE_LIVE, Prefs.HIDE_SHELVES, Prefs.SUBS_ONLY_VIDEOS, Prefs.REFRESH_COUNT, Prefs.HIDE_SEARCH_EXTRAS,
            Prefs.REMOTE_RULES, Prefs.UPDATE_MIRROR, Prefs.AMOLED, Prefs.FONT_FOLLOW, Prefs.COMPACT, Prefs.CARD_STYLE,
            Prefs.DIM_THUMBS, Prefs.LONG_PRESS, Prefs.SEARCH_FOCUS, Prefs.SEARCH_CHIPS, Prefs.KEEP_SCROLL, Prefs.HAPTIC,
            Prefs.POWER_SAVE_AUTO, Prefs.NOTIF_WL,
            Prefs.WIFI_SAVER, Prefs.IDLE_GUARD, Prefs.SKIP_WATCHED_NEXT, Prefs.FONT_LARGE, Prefs.ORIENT_REMEMBER,
            Prefs.HIDE_WATCHED_RELATED, Prefs.HIDE_SEARCH_MIX, Prefs.LIGHT_REFRESH, Prefs.SLEEP_HOLD, Prefs.SLEEP_MODE_BLACK,
            Prefs.LIST_END_NEXT, Prefs.SLEEP_STRICT, Prefs.PRE_RASTER, Prefs.LANDSCAPE_LAYOUT};
    public static final String[] INT_KEYS = {Prefs.SLEEP_DEFAULT, Prefs.SLEEP_FADE, Prefs.SEEN_MIN, Prefs.MIN_DUR, Prefs.MAX_AGE,
            Prefs.NIGHT_START, Prefs.NIGHT_END, Prefs.ORIENT_LOCK, Prefs.EXTEND_STEP};
    public static final String[] STR_KEYS = {Prefs.SPONSOR_CATS, Prefs.NEXT_SOURCE, Prefs.QUALITY, Prefs.RATE, Prefs.CAPTIONS,
            Prefs.SUB_SIZE, Prefs.SUB_BG, Prefs.BLOCK_CHANNELS, Prefs.BLOCK_WORDS, Prefs.START_PAGE, Prefs.ACCENT};

    /** The user's block lists: only counts go into a problem report (413). */
    public static final String[] KEEP_ON_DEFAULTS = {Prefs.BLOCK_CHANNELS, Prefs.BLOCK_WORDS};

    public static final String[] SB_CATS = {"sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic", "filler"};

    // ---------- defaults ----------

    public static boolean defBool(String key) {
        switch (key) {
            case Prefs.NORMALIZE: case Prefs.REMEMBER_RATE: case Prefs.SLEEP_CALM: case Prefs.NOTIF_SEEK:
            case Prefs.HIDE_LIVE: case Prefs.HIDE_SHELVES: case Prefs.SUBS_ONLY_VIDEOS: case Prefs.UPDATE_MIRROR: case Prefs.AMOLED:
            case Prefs.COMPACT: case Prefs.CARD_STYLE: case Prefs.DIM_THUMBS:
            case Prefs.WIFI_SAVER: case Prefs.SKIP_WATCHED_NEXT: case Prefs.FONT_LARGE: case Prefs.ORIENT_REMEMBER:
            case Prefs.HIDE_WATCHED_RELATED: case Prefs.HIDE_SEARCH_MIX: case Prefs.LIGHT_REFRESH:
            case Prefs.SLEEP_HOLD: case Prefs.SLEEP_MODE_BLACK: case Prefs.LIST_END_NEXT: case Prefs.SLEEP_STRICT:
            case Prefs.PRE_RASTER: case Prefs.LANDSCAPE_LAYOUT:
                return false;
            default: return true;
        }
    }

    public static int defInt(String key) {
        switch (key) {
            case Prefs.SLEEP_DEFAULT: return 30;
            case Prefs.SLEEP_FADE: return 60;
            case Prefs.SEEN_MIN: return 80;
            case Prefs.NIGHT_START: return 22;
            case Prefs.NIGHT_END: return 5;
            case Prefs.EXTEND_STEP: return 15;
            default: return 0;
        }
    }

    public static String defStr(String key) {
        switch (key) {
            case Prefs.SPONSOR_CATS: return "sponsor";
            case Prefs.NEXT_SOURCE: return "rec";
            case Prefs.QUALITY: return "auto";
            case Prefs.RATE: return "1";
            case Prefs.CAPTIONS: return "yt";
            case Prefs.SUB_SIZE: case Prefs.SUB_BG: return "default";
            case Prefs.START_PAGE: return "home";
            default: return "";
        }
    }

    public static boolean isBool(String k) { return Arrays.asList(BOOL_KEYS).contains(k); }
    public static boolean isInt(String k) { return Arrays.asList(INT_KEYS).contains(k); }
    public static boolean isStr(String k) { return Arrays.asList(STR_KEYS).contains(k); }

    // ---------- storage abstraction ----------

    /** What the model needs from a key/value store (SharedPreferences in the app, a map in tests). */
    public interface Store {
        Object get(String key);
        void put(String key, Object value);
        void remove(String key);
    }

    /** In-memory store (tests, snapshots). */
    public static final class MapStore implements Store {
        public final Map<String, Object> map = new LinkedHashMap<>();
        @Override public Object get(String key) { return map.get(key); }
        @Override public void put(String key, Object value) { map.put(key, value); }
        @Override public void remove(String key) { map.remove(key); }
    }

    public static List<String> allKeys() {
        List<String> l = new ArrayList<>(Arrays.asList(BOOL_KEYS));
        l.addAll(Arrays.asList(INT_KEYS));
        l.addAll(Arrays.asList(STR_KEYS));
        return l;
    }

    // ---------- schema migration (226) ----------

    /**
     * Step the stored settings up to {@link #SCHEMA}. Idempotent: running it again changes nothing.
     * v2 only records the schema and drops the old pseudo key if it ever got stored; v3 (v1.15) drops the
     * state the removed features left behind (settings snapshot, sleep timer state, night log, mirror).
     * No user setting is renamed, retyped or removed: the keys of removed settings keep their values
     * (never read). Returns true when something was written.
     *
     * Future renames: v(N) reads the old key and writes the new one keeping the old, v(N+1) still
     * reads the old one, v(N+2) deletes it; backups go through an alias table.
     */
    public static boolean migrate(Store s) {
        int from = 0;
        Object cur = s.get(PREFS_SCHEMA);
        if (cur instanceof Number) from = ((Number) cur).intValue();
        if (from >= SCHEMA) return false;
        if (from < 2) {
            if (s.get(OLD_COMPACT_CHOICE) != null) s.remove(OLD_COMPACT_CHOICE);
        }
        if (from < 3) {
            for (String k : REMOVED_STATE) if (s.get(k) != null) s.remove(k);
        }
        s.put(PREFS_SCHEMA, SCHEMA);
        return true;
    }

    // ---------- backup (98 / 241) ----------

    /** Known settings that are actually stored (absent keys stay absent so defaults keep working). */
    public static Map<String, Object> exportMap(Store s) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : allKeys()) {
            Object v = s.get(k);
            if (v != null && validateType(k, v)) out.put(k, v);
        }
        return out;
    }

    private static boolean validateType(String k, Object v) {
        if (isBool(k)) return v instanceof Boolean;
        if (isInt(k)) return v instanceof Number;
        return v instanceof String;
    }

    // ---------- merged rows (229 / 237) ----------

    /** Watched row: 0 = don't hide (seenMin kept for later), else hide at 50 / 80 / 95 %. */
    public static int watchedChoice(boolean hideWatched, int seenMin) {
        if (!hideWatched) return 0;
        return seenMin == 50 || seenMin == 95 ? seenMin : 80;
    }

    public static void writeWatchedChoice(Store s, int choice) {
        if (choice == 0) {
            s.put(Prefs.HIDE_WATCHED, false);
        } else {
            s.put(Prefs.HIDE_WATCHED, true);
            s.put(Prefs.SEEN_MIN, choice);
        }
    }

    /**
     * Settings as they go into a problem report (413): the block lists only as counts, so a report
     * never carries the user's channels and keywords.
     */
    public static Map<String, Object> redactForReport(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>(m);
        for (String k : KEEP_ON_DEFAULTS) {
            Object v = out.get(k);
            if (v == null) continue;
            int n = 0;
            for (String line : String.valueOf(v).split("\n")) if (!line.trim().isEmpty()) n++;
            out.put(k, n + " 条（内容不随报告发送）");
        }
        return out;
    }

    /** List style row summary (476). */
    public static String listStyleLabel(boolean compact, boolean cards, boolean dim) {
        if (!compact && !cards && !dim) return "默认";
        StringBuilder b = new StringBuilder();
        if (compact) b.append("紧凑");
        if (cards) { if (b.length() > 0) b.append(" · "); b.append("统一卡片"); }
        if (dim) { if (b.length() > 0) b.append(" · "); b.append("压暗缩略图"); }
        return b.toString();
    }

    /** Smoothness row summary (476). */
    public static String perfLabel(boolean perfCss, boolean preRaster) {
        return (perfCss ? "列表渲染优化 开" : "列表渲染优化 关") + (preRaster ? " · 预渲染 开" : "");
    }

    /** Notification buttons, as the row summary shows them (237). */
    public static String notifLayout(boolean seek, boolean watchLater) {
        return (seek ? "快退 10 秒 · 播放 · 快进 30 秒" : "上一个 · 播放 · 下一个") + (watchLater ? " · 稍后观看" : "");
    }

    // ---------- merged rows, round 2 (455 / 459) ----------

    /** Web text size row (455): 0 fixed, 1 follow the system (85–130 %), 2 follow, very large allowed (≤160 %). */
    public static int fontChoice(boolean follow, boolean large) {
        return !follow ? 0 : large ? 2 : 1;
    }

    /** Writes both keys; "fixed" keeps fontLarge as it was so switching back restores it. */
    public static void writeFontChoice(Store s, int choice) {
        if (choice <= 0) { s.put(Prefs.FONT_FOLLOW, false); return; }
        s.put(Prefs.FONT_FOLLOW, true);
        s.put(Prefs.FONT_LARGE, choice >= 2);
    }

    /** Search results "only videos and channels" (459): on when either of the two hiding rules is on. */
    public static boolean searchTidy(boolean extras, boolean mix) {
        return extras || mix;
    }

    public static void writeSearchTidy(Store s, boolean on) {
        s.put(Prefs.HIDE_SEARCH_EXTRAS, on);
        s.put(Prefs.HIDE_SEARCH_MIX, on);
    }
}
