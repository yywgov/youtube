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
            Prefs.WIFI_SAVER, Prefs.IDLE_GUARD, Prefs.SKIP_WATCHED_NEXT, Prefs.FONT_LARGE, Prefs.ORIENT_REMEMBER,
            Prefs.HIDE_WATCHED_RELATED, Prefs.HIDE_SEARCH_MIX, Prefs.LIGHT_REFRESH, Prefs.SLEEP_HOLD, Prefs.SLEEP_MODE_BLACK,
            Prefs.LIST_END_NEXT, Prefs.SLEEP_STRICT, Prefs.PRE_RASTER, Prefs.LANDSCAPE_LAYOUT};
    public static final String[] INT_KEYS = {Prefs.SLEEP_DEFAULT, Prefs.SLEEP_FADE, Prefs.SEEN_MIN, Prefs.MIN_DUR, Prefs.MAX_AGE,
            Prefs.NIGHT_START, Prefs.NIGHT_END, Prefs.ORIENT_LOCK, Prefs.EXTEND_STEP};
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
        INT_SETS.put(Prefs.EXTEND_STEP, new HashSet<>(Arrays.asList(10, 15, 30)));
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

    // ---------- import preview (404) ----------

    /** Short Chinese name of each setting for the import preview; the key itself when missing. */
    static final Map<String, String> LABELS = new java.util.HashMap<>();
    static {
        String[] kv = {
                Prefs.SPONSOR_BLOCK, "SponsorBlock 自动跳过", Prefs.LOW_QUALITY_BG, "后台/息屏最低画质", Prefs.HIDE_WATCHED, "隐藏已看完",
                Prefs.PERF_CSS, "列表渲染优化", Prefs.SLEEP_REMEMBER, "睡眠默认时长沿用上次", Prefs.SLEEP_BADGE, "播放器角上剩余时间",
                Prefs.NIGHT_PROMPT, "夜间提示开睡眠定时", Prefs.SLEEP_CALM, "睡眠时优先同频道", Prefs.NOTIF_SEEK, "通知栏快退/快进键",
                Prefs.HEADSET_MULTI, "耳机按键多击", Prefs.RESUME_PROMPT, "重开时提示继续", Prefs.RESUME_LOCAL, "未登录记住进度",
                Prefs.REMEMBER_RATE, "记住播放速度", Prefs.ORIG_AUDIO, "优先原声音轨", Prefs.NO_END_WALL, "不显示片尾推荐墙",
                Prefs.NORMALIZE, "音量均衡", Prefs.HIDE_LIVE, "隐藏直播", Prefs.HIDE_SHELVES, "隐藏首页货架", Prefs.SUBS_ONLY_VIDEOS, "订阅页只看视频",
                Prefs.REFRESH_COUNT, "刷新后提示新视频数", Prefs.HIDE_SEARCH_EXTRAS, "搜索隐藏插入模块", Prefs.REMOTE_RULES, "远程屏蔽规则",
                Prefs.UPDATE_MIRROR, "镜像下载更新", Prefs.AMOLED, "纯黑主题", Prefs.FONT_FOLLOW, "字体跟随系统", Prefs.COMPACT, "紧凑列表",
                Prefs.CARD_STYLE, "统一卡片样式", Prefs.DIM_THUMBS, "压暗缩略图", Prefs.LONG_PRESS, "长按菜单", Prefs.SEARCH_FOCUS, "搜索自动弹键盘",
                Prefs.SEARCH_CHIPS, "搜索快捷筛选", Prefs.KEEP_SCROLL, "返回保持位置", Prefs.HAPTIC, "轻震提示", Prefs.POWER_SAVE_AUTO, "省电模式降画质",
                Prefs.NOTIF_WL, "通知栏稍后观看", Prefs.WIFI_SAVER, "息屏省电 Wi-Fi", Prefs.IDLE_GUARD, "无操作保护",
                Prefs.SKIP_WATCHED_NEXT, "推荐跳过看过的", Prefs.FONT_LARGE, "允许超大字号", Prefs.ORIENT_REMEMBER, "记住屏幕方向",
                Prefs.HIDE_WATCHED_RELATED, "相关视频也隐藏已看完", Prefs.HIDE_SEARCH_MIX, "搜索隐藏合辑", Prefs.LIGHT_REFRESH, "轻量刷新",
                Prefs.SLEEP_HOLD, "暂停时定时也暂停", Prefs.SLEEP_MODE_BLACK, "睡眠模式自动黑屏", Prefs.LIST_END_NEXT, "列表播完接推荐",
                Prefs.SLEEP_STRICT, "睡眠时只同频道", Prefs.PRE_RASTER, "预渲染屏外内容", Prefs.LANDSCAPE_LAYOUT, "横屏播放器 70% 高",
                Prefs.SLEEP_DEFAULT, "睡眠默认时长", Prefs.SLEEP_FADE, "淡出时长", Prefs.SEEN_MIN, "看过多少算已看", Prefs.MIN_DUR, "隐藏短视频",
                Prefs.MAX_AGE, "隐藏旧视频", Prefs.NIGHT_START, "夜间开始", Prefs.NIGHT_END, "夜间结束", Prefs.ORIENT_LOCK, "屏幕方向锁定",
                Prefs.EXTEND_STEP, "延长按钮步长", Prefs.SPONSOR_CATS, "SponsorBlock 类别", Prefs.NEXT_SOURCE, "接下来播放",
                Prefs.QUALITY, "默认画质", Prefs.RATE, "播放速度", Prefs.CAPTIONS, "默认字幕", Prefs.SUB_SIZE, "字幕大小", Prefs.SUB_BG, "字幕背景",
                Prefs.BLOCK_CHANNELS, "屏蔽的频道", Prefs.BLOCK_WORDS, "屏蔽的关键词", Prefs.START_PAGE, "启动页", Prefs.ACCENT, "强调色"};
        for (int i = 0; i + 1 < kv.length; i += 2) LABELS.put(kv[i], kv[i + 1]);
    }

    public static String label(String key) {
        String l = LABELS.get(key);
        return l == null ? key : l;
    }

    private static Object effective(Store s, String k) {
        Object v = s.get(k);
        if (v != null) return v;
        if (isBool(k)) return defBool(k);
        if (isInt(k)) return defInt(k);
        return defStr(k);
    }

    private static String show(String k, Object v) {
        if (v instanceof Boolean) return (Boolean) v ? "开" : "关";
        String s = String.valueOf(v);
        if (Prefs.BLOCK_CHANNELS.equals(k) || Prefs.BLOCK_WORDS.equals(k)) {
            int n = 0;
            for (String x : s.split("\n")) if (!x.trim().isEmpty()) n++;
            return n + " 个";
        }
        return s.isEmpty() ? "默认" : s.length() > 24 ? s.substring(0, 24) + "…" : s;
    }

    /**
     * What an import would change (404), as {key, label, before, after} rows, using the same checks as
     * {@link #importMap}; null when the text is not a backup.
     */
    public static List<String[]> importDiff(Map<String, Object> b, Store cur) {
        MapStore copy = new MapStore();
        for (String k : allKeys()) { Object v = cur.get(k); if (v != null) copy.put(k, v); }
        ImportResult r = importMap(b, copy, false);
        if (r.notBackup) return null;
        List<String[]> out = new java.util.ArrayList<>();
        for (String k : allKeys()) {
            Object x = effective(cur, k), y = effective(copy, k);
            if (!String.valueOf(x).equals(String.valueOf(y))) out.add(new String[]{k, label(k), show(k, x), show(k, y)});
        }
        return out;
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
