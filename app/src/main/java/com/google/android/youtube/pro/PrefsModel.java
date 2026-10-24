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
 * the backup whitelist, defaults, allowed values, the schema migration (226), backup v2
 * validation (241), "restore defaults" without the block lists (240) and the read/write
 * mappings used where one settings row edits two keys (228/229).
 *
 * Settings merge rule (v1.6): key names, types and meanings never change; a merged row is
 * only a view over the same keys, so an upgrade keeps every value as it was.
 */
public final class PrefsModel {
    private PrefsModel() {}

    /** Settings schema written by this version (state, never part of a backup). */
    public static final int SCHEMA = 2;
    public static final String PREFS_SCHEMA = "prefsSchema";
    /** Settings JSON taken right before "restore defaults" / import, for undo (406). State. */
    public static final String SNAPSHOT = "lastSnapshot";
    /** Pseudo key the v1.4–v1.5 layout row used in code; never meant to be stored (236). */
    public static final String OLD_COMPACT_CHOICE = Prefs.COMPACT + "_choice";

    /** Every user setting (not state): backup / restore (98) and "restore defaults" (136). */
    public static final String[] BOOL_KEYS = {Prefs.SPONSOR_BLOCK, Prefs.LOW_QUALITY_BG, Prefs.HIDE_WATCHED, Prefs.PERF_CSS,
            Prefs.SLEEP_REMEMBER, Prefs.SLEEP_BADGE, Prefs.NIGHT_PROMPT, Prefs.SLEEP_CALM, Prefs.NOTIF_SEEK, Prefs.HEADSET_MULTI,
            Prefs.RESUME_PROMPT, Prefs.RESUME_LOCAL, Prefs.REMEMBER_RATE, Prefs.ORIG_AUDIO, Prefs.NO_END_WALL, Prefs.NORMALIZE,
            Prefs.HIDE_LIVE, Prefs.HIDE_SHELVES, Prefs.SUBS_ONLY_VIDEOS, Prefs.REFRESH_COUNT, Prefs.HIDE_SEARCH_EXTRAS,
            Prefs.REMOTE_RULES, Prefs.UPDATE_MIRROR, Prefs.AMOLED, Prefs.FONT_FOLLOW, Prefs.COMPACT, Prefs.CARD_STYLE,
            Prefs.DIM_THUMBS, Prefs.LONG_PRESS, Prefs.SEARCH_FOCUS, Prefs.SEARCH_CHIPS, Prefs.KEEP_SCROLL, Prefs.HAPTIC,
            Prefs.POWER_SAVE_AUTO, Prefs.NOTIF_WL,
            Prefs.WIFI_SAVER, Prefs.IDLE_GUARD, Prefs.SKIP_WATCHED_NEXT, Prefs.FONT_LARGE, Prefs.ORIENT_REMEMBER};
    public static final String[] INT_KEYS = {Prefs.SLEEP_DEFAULT, Prefs.SLEEP_FADE, Prefs.SEEN_MIN, Prefs.MIN_DUR, Prefs.MAX_AGE,
            Prefs.NIGHT_START, Prefs.NIGHT_END, Prefs.ORIENT_LOCK};
    public static final String[] STR_KEYS = {Prefs.SPONSOR_CATS, Prefs.NEXT_SOURCE, Prefs.QUALITY, Prefs.RATE, Prefs.CAPTIONS,
            Prefs.SUB_SIZE, Prefs.SUB_BG, Prefs.BLOCK_CHANNELS, Prefs.BLOCK_WORDS, Prefs.START_PAGE, Prefs.ACCENT};

    /** Kept by "restore defaults" (240); cleared only by their own "clear block lists" row. */
    public static final String[] KEEP_ON_DEFAULTS = {Prefs.BLOCK_CHANNELS, Prefs.BLOCK_WORDS};

    public static final String[] SB_CATS = {"sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic", "filler"};

    // ---------- defaults ----------

    public static boolean defBool(String key) {
        switch (key) {
            case Prefs.NORMALIZE: case Prefs.REMEMBER_RATE: case Prefs.SLEEP_CALM: case Prefs.NOTIF_SEEK:
            case Prefs.HIDE_LIVE: case Prefs.HIDE_SHELVES: case Prefs.SUBS_ONLY_VIDEOS: case Prefs.UPDATE_MIRROR: case Prefs.AMOLED:
            case Prefs.COMPACT: case Prefs.CARD_STYLE: case Prefs.DIM_THUMBS:
            case Prefs.WIFI_SAVER: case Prefs.SKIP_WATCHED_NEXT: case Prefs.FONT_LARGE: case Prefs.ORIENT_REMEMBER:
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

    // ---------- allowed values (241) ----------

    private static final Map<String, Set<String>> ENUMS = new LinkedHashMap<>();
    private static final Map<String, Set<Integer>> INT_SETS = new LinkedHashMap<>();
    static {
        ENUMS.put(Prefs.NEXT_SOURCE, set("rec", "channel", "playlist"));
        ENUMS.put(Prefs.QUALITY, set("auto", "wifiMax", "1080", "720", "480"));
        ENUMS.put(Prefs.CAPTIONS, set("yt", "off", "zh", "zhHans", "zhHant", "en"));
        ENUMS.put(Prefs.SUB_SIZE, set("default", "s", "l", "xl"));
        ENUMS.put(Prefs.SUB_BG, set("default", "half", "none"));
        ENUMS.put(Prefs.START_PAGE, set("home", "subs"));
        INT_SETS.put(Prefs.SLEEP_FADE, new HashSet<>(Arrays.asList(10, 30, 60)));
        INT_SETS.put(Prefs.SEEN_MIN, new HashSet<>(Arrays.asList(50, 80, 95)));
        INT_SETS.put(Prefs.MIN_DUR, new HashSet<>(Arrays.asList(0, 1, 3, 5, 10)));
        INT_SETS.put(Prefs.MAX_AGE, new HashSet<>(Arrays.asList(0, 7, 30, 180, 365)));
        Set<Integer> hours = new HashSet<>();
        for (int h = 0; h < 24; h++) hours.add(h);
        INT_SETS.put(Prefs.NIGHT_START, hours);
        INT_SETS.put(Prefs.NIGHT_END, hours);
        INT_SETS.put(Prefs.ORIENT_LOCK, new HashSet<>(Arrays.asList(0, 1, 2)));
    }

    private static Set<String> set(String... v) { return new HashSet<>(Arrays.asList(v)); }

    public static boolean isBool(String k) { return Arrays.asList(BOOL_KEYS).contains(k); }
    public static boolean isInt(String k) { return Arrays.asList(INT_KEYS).contains(k); }
    public static boolean isStr(String k) { return Arrays.asList(STR_KEYS).contains(k); }

    /** Sleep modes a default may hold: minutes 1..600, end of video, after 2/3/5 videos. */
    public static boolean validSleepMode(int m) {
        return (m > 0 && m <= 600) || m == -1 || m == -102 || m == -103 || m == -105;
    }

    /**
     * The value to store for {@code key}, or null when the key is unknown or the value is not
     * one this version accepts (wrong type, out of range, not one of the options).
     */
    public static Object validate(String key, Object v) {
        if (key == null || v == null) return null;
        if (isBool(key)) return v instanceof Boolean ? v : null;
        if (isInt(key)) {
            if (!(v instanceof Number)) return null;
            double d = ((Number) v).doubleValue();
            if (d != Math.rint(d) || Math.abs(d) > 100000) return null;
            int i = (int) d;
            if (Prefs.SLEEP_DEFAULT.equals(key)) return validSleepMode(i) ? i : null;
            Set<Integer> ok = INT_SETS.get(key);
            return ok == null || ok.contains(i) ? i : null;
        }
        if (isStr(key)) {
            if (!(v instanceof String)) return null;
            String s = (String) v;
            if (s.length() >= 4000) return null;
            Set<String> ok = ENUMS.get(key);
            if (ok != null) return ok.contains(s) ? s : null;
            switch (key) {
                case Prefs.ACCENT:
                    return s.isEmpty() || s.matches("#[0-9a-fA-F]{6}") ? s : null;
                case Prefs.RATE:
                    try {
                        double r = Double.parseDouble(s);
                        return r >= 0.25 && r <= 2 ? s : null;
                    } catch (Exception e) {
                        return null;
                    }
                case Prefs.SPONSOR_CATS:
                    if (s.trim().isEmpty()) return "";
                    for (String c : s.split(",")) if (!Arrays.asList(SB_CATS).contains(c.trim())) return null;
                    return s;
                default:
                    return s; // block lists: free text, one entry per line
            }
        }
        return null;
    }

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
     * v2 only records the schema and drops the old pseudo key if it ever got stored; no user value
     * is renamed, retyped or removed. Returns true when something was written.
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

    /** Result of an import: applied / ignored counts, or notBackup. */
    public static final class ImportResult {
        public int applied, ignored;
        public boolean notBackup;
        public int version;
    }

    public static boolean isBackupApp(Object app) {
        return "YiWei".equals(app) || "YT Lite".equals(app);
    }

    /**
     * Import a parsed backup (v1 or v2: same keys). Unknown keys and the header fields are ignored,
     * known keys with a value this version does not accept are skipped and counted.
     * With {@code replace} (undo of a snapshot) known keys missing from the backup are removed too.
     */
    public static ImportResult importMap(Map<String, Object> b, Store s, boolean replace) {
        ImportResult r = new ImportResult();
        if (b == null || !isBackupApp(b.get("app"))) { r.notBackup = true; return r; }
        Object v = b.get("v");
        r.version = v instanceof Number ? ((Number) v).intValue() : 1;
        for (String k : allKeys()) {
            if (!b.containsKey(k)) {
                if (replace) s.remove(k);
                continue;
            }
            Object ok = validate(k, b.get(k));
            if (ok == null) { r.ignored++; continue; }
            s.put(k, ok);
            r.applied++;
        }
        return r;
    }

    /** "Restore defaults" (136/240): every setting back to its default, block lists kept. */
    public static void restoreDefaults(Store s) {
        List<String> keep = Arrays.asList(KEEP_ON_DEFAULTS);
        for (String k : allKeys()) if (!keep.contains(k)) s.remove(k);
    }

    // ---------- merged rows (228 / 229 / 237) ----------

    /** Sleep default row: 0 = "same as the last timer started" (sleepRemember), else the fixed mode. */
    public static final int SLEEP_CHOICE_LAST = 0;

    public static int sleepChoice(boolean remember, int sleepDefault) {
        return remember ? SLEEP_CHOICE_LAST : sleepDefault;
    }

    public static void writeSleepChoice(Store s, int choice) {
        if (choice == SLEEP_CHOICE_LAST) {
            s.put(Prefs.SLEEP_REMEMBER, true);
        } else {
            s.put(Prefs.SLEEP_DEFAULT, choice);
            s.put(Prefs.SLEEP_REMEMBER, false);
        }
    }

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

    /** Night window label (307), e.g. "22:00–5:00". */
    public static String nightLabel(int start, int end) {
        return start + ":00–" + end + ":00";
    }

    /** Notification buttons, as the row summary shows them (237). */
    public static String notifLayout(boolean seek, boolean watchLater) {
        return (seek ? "快退 10 秒 · 播放 · 快进 30 秒" : "上一个 · 播放 · 下一个") + " · 睡眠" + (watchLater ? " · 稍后观看" : "");
    }
}
