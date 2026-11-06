package com.google.android.youtube.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Settings merge (v1.6): only the view changed, never the stored keys (226/228/229/240/241, 417). */
public class PrefsModelTest {

    private static PrefsModel.MapStore v151Store() {
        PrefsModel.MapStore s = new PrefsModel.MapStore();
        s.put(Prefs.SPONSOR_BLOCK, false);
        s.put(Prefs.SLEEP_REMEMBER, false);
        s.put(Prefs.SLEEP_DEFAULT, -103);
        s.put(Prefs.SEEN_MIN, 95);
        s.put(Prefs.HIDE_WATCHED, false);
        s.put(Prefs.QUALITY, "720");
        s.put(Prefs.BLOCK_CHANNELS, "A\nB");
        s.put(Prefs.BLOCK_WORDS, "spoiler");
        s.put(Prefs.COMPACT, true);
        s.put(Prefs.LAST_VIDEO, "abcdefghijk");   // state, not a setting
        return s;
    }

    static final int B6_BOOL = 9, B6_INT = 1;

    @Test public void keyListsUnchanged() {
        // v1.5.1's 51 keys + batch 5's 8 + batch 6's new ones (never a rename)
        assertEquals(35 + 5 + B6_BOOL, PrefsModel.BOOL_KEYS.length);
        assertEquals(5 + 3 + B6_INT, PrefsModel.INT_KEYS.length);
        assertEquals(11, PrefsModel.STR_KEYS.length);
        assertEquals(59 + B6_BOOL + B6_INT, new java.util.HashSet<>(PrefsModel.allKeys()).size());
    }

    @Test public void migrateWritesSchemaKeepsEveryValueAndIsIdempotent() {
        PrefsModel.MapStore s = v151Store();
        s.put(PrefsModel.OLD_COMPACT_CHOICE, "compact");
        Map<String, Object> before = new HashMap<>(s.map);
        before.remove(PrefsModel.OLD_COMPACT_CHOICE);
        assertTrue(PrefsModel.migrate(s));
        assertEquals(PrefsModel.SCHEMA, s.get(PrefsModel.PREFS_SCHEMA));
        assertNull(s.get(PrefsModel.OLD_COMPACT_CHOICE));
        for (Map.Entry<String, Object> e : before.entrySet()) assertEquals(e.getKey(), e.getValue(), s.get(e.getKey()));
        Map<String, Object> once = new HashMap<>(s.map);
        assertFalse(PrefsModel.migrate(s));
        assertEquals(once, s.map);
    }

    @Test public void migrateOnFreshInstall() {
        PrefsModel.MapStore s = new PrefsModel.MapStore();
        assertTrue(PrefsModel.migrate(s));
        assertEquals(1, s.map.size());
    }

    @Test public void exportOnlyKnownStoredKeys() {
        Map<String, Object> m = PrefsModel.exportMap(v151Store());
        assertEquals(9, m.size());
        assertFalse(m.containsKey(Prefs.LAST_VIDEO));
        assertFalse(m.containsKey(PrefsModel.PREFS_SCHEMA));
    }

    /** 229: "don't hide" keeps seenMin; every old pair round-trips. */
    @Test public void watchedMappingAllOldValues() {
        for (boolean hide : new boolean[]{true, false}) {
            for (int seen : new int[]{50, 80, 95}) {
                int c = PrefsModel.watchedChoice(hide, seen);
                assertEquals(hide ? seen : 0, c);
                PrefsModel.MapStore s = new PrefsModel.MapStore();
                s.put(Prefs.HIDE_WATCHED, hide);
                s.put(Prefs.SEEN_MIN, seen);
                PrefsModel.writeWatchedChoice(s, c);
                assertEquals(hide, s.get(Prefs.HIDE_WATCHED));
                assertEquals(seen, s.get(Prefs.SEEN_MIN));
            }
        }
    }

    @Test public void notificationLayoutSummary() {
        assertEquals("上一个 · 播放 · 下一个 · 稍后观看", PrefsModel.notifLayout(false, true));
        assertEquals("快退 10 秒 · 播放 · 快进 30 秒", PrefsModel.notifLayout(true, false));
    }

    @Test public void booleanDefaultsUnchanged() {
        String[] off = {Prefs.NORMALIZE, Prefs.REMEMBER_RATE, Prefs.SLEEP_CALM, Prefs.NOTIF_SEEK, Prefs.HIDE_LIVE, Prefs.HIDE_SHELVES,
                Prefs.SUBS_ONLY_VIDEOS, Prefs.UPDATE_MIRROR, Prefs.COMPACT, Prefs.CARD_STYLE, Prefs.DIM_THUMBS,
                // batch 5: everything that could change playback / Wi-Fi behaviour starts off
                Prefs.WIFI_SAVER, Prefs.SKIP_WATCHED_NEXT, Prefs.FONT_LARGE, Prefs.ORIENT_REMEMBER,
                // batch 6: new behaviour is opt-in
                Prefs.HIDE_WATCHED_RELATED, Prefs.HIDE_SEARCH_MIX, Prefs.LIGHT_REFRESH, Prefs.SLEEP_HOLD, Prefs.SLEEP_MODE_BLACK,
                Prefs.LIST_END_NEXT, Prefs.SLEEP_STRICT, Prefs.PRE_RASTER, Prefs.LANDSCAPE_LAYOUT};
        java.util.List<String> offL = java.util.Arrays.asList(off);
        for (String k : PrefsModel.BOOL_KEYS) assertEquals(k, !offL.contains(k), PrefsModel.defBool(k));
        assertEquals(30, PrefsModel.defInt(Prefs.SLEEP_DEFAULT));
        assertEquals(60, PrefsModel.defInt(Prefs.SLEEP_FADE));
        assertEquals(80, PrefsModel.defInt(Prefs.SEEN_MIN));
    }
}
