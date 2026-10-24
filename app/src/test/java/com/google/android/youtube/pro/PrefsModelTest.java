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

    @Test public void keyListsUnchanged() {
        assertEquals(35, PrefsModel.BOOL_KEYS.length);
        assertEquals(5, PrefsModel.INT_KEYS.length);
        assertEquals(11, PrefsModel.STR_KEYS.length);
        assertEquals(51, new java.util.HashSet<>(PrefsModel.allKeys()).size());
    }

    @Test public void migrateWritesSchemaKeepsEveryValueAndIsIdempotent() {
        PrefsModel.MapStore s = v151Store();
        s.put(PrefsModel.OLD_COMPACT_CHOICE, "compact");
        Map<String, Object> before = new HashMap<>(s.map);
        before.remove(PrefsModel.OLD_COMPACT_CHOICE);
        assertTrue(PrefsModel.migrate(s));
        assertEquals(2, s.get(PrefsModel.PREFS_SCHEMA));
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

    @Test public void restoreDefaultsKeepsBlockListsAndState() {
        PrefsModel.MapStore s = v151Store();
        PrefsModel.restoreDefaults(s);
        assertEquals("A\nB", s.get(Prefs.BLOCK_CHANNELS));
        assertEquals("spoiler", s.get(Prefs.BLOCK_WORDS));
        assertEquals("abcdefghijk", s.get(Prefs.LAST_VIDEO));
        assertNull(s.get(Prefs.SPONSOR_BLOCK));
        assertNull(s.get(Prefs.QUALITY));
        assertNull(s.get(Prefs.COMPACT));
    }

    @Test public void exportOnlyKnownStoredKeys() {
        Map<String, Object> m = PrefsModel.exportMap(v151Store());
        assertEquals(9, m.size());
        assertFalse(m.containsKey(Prefs.LAST_VIDEO));
        assertFalse(m.containsKey(PrefsModel.PREFS_SCHEMA));
    }

    @Test public void backupV1AndV2RoundTrip() {
        Map<String, Object> exported = PrefsModel.exportMap(v151Store());
        for (int v = 1; v <= 2; v++) {
            for (String app : new String[]{"YiWei", "YT Lite"}) {
                Map<String, Object> b = new LinkedHashMap<>(exported);
                b.put("app", app);
                b.put("v", v);
                if (v == 2) { b.put("schema", 2); b.put("exportedAt", 1730000000000L); b.put("appVersion", "1.6.0"); }
                PrefsModel.MapStore target = new PrefsModel.MapStore();
                PrefsModel.ImportResult r = PrefsModel.importMap(b, target, false);
                assertFalse(r.notBackup);
                assertEquals(9, r.applied);
                assertEquals(0, r.ignored);
                assertEquals(exported, PrefsModel.exportMap(target));
            }
        }
    }

    @Test public void foreignTextIsNotABackup() {
        Map<String, Object> b = new HashMap<>();
        b.put("app", "Other");
        b.put(Prefs.QUALITY, "720");
        assertTrue(PrefsModel.importMap(b, new PrefsModel.MapStore(), false).notBackup);
    }

    @Test public void badValuesAreSkippedAndCounted() {
        Map<String, Object> b = new HashMap<>();
        b.put("app", "YiWei");
        b.put(Prefs.SEEN_MIN, 7);
        b.put(Prefs.MIN_DUR, -3);
        b.put(Prefs.SLEEP_FADE, 45);
        b.put(Prefs.SLEEP_DEFAULT, -104);
        b.put(Prefs.QUALITY, "4k");
        b.put(Prefs.ACCENT, "red");
        b.put(Prefs.RATE, "16");
        b.put(Prefs.SPONSOR_CATS, "sponsor,ads");
        b.put(Prefs.AMOLED, "yes");
        b.put(Prefs.MAX_AGE, 30);
        b.put(Prefs.SLEEP_DEFAULT + "x", 1);
        PrefsModel.MapStore s = new PrefsModel.MapStore();
        PrefsModel.ImportResult r = PrefsModel.importMap(b, s, false);
        assertEquals(1, r.applied);
        assertEquals(9, r.ignored);
        assertEquals(30, s.get(Prefs.MAX_AGE));
        assertNull(s.get(Prefs.SEEN_MIN));
    }

    @Test public void validValuesAccepted() {
        assertEquals(-105, PrefsModel.validate(Prefs.SLEEP_DEFAULT, -105));
        assertEquals(-1, PrefsModel.validate(Prefs.SLEEP_DEFAULT, -1));
        assertEquals(90, PrefsModel.validate(Prefs.SLEEP_DEFAULT, 90L));
        assertNull(PrefsModel.validate(Prefs.SLEEP_DEFAULT, 0));
        assertNull(PrefsModel.validate(Prefs.SEEN_MIN, 80.5));
        assertEquals("", PrefsModel.validate(Prefs.SPONSOR_CATS, ""));
        assertEquals("#2F6BFF", PrefsModel.validate(Prefs.ACCENT, "#2F6BFF"));
        assertEquals("1.25", PrefsModel.validate(Prefs.RATE, "1.25"));
        assertEquals("wifiMax", PrefsModel.validate(Prefs.QUALITY, "wifiMax"));
    }

    @Test public void undoSnapshotReplacesExactly() {
        PrefsModel.MapStore s = v151Store();
        Map<String, Object> snap = new LinkedHashMap<>(PrefsModel.exportMap(s));
        snap.put("app", "YiWei");
        PrefsModel.restoreDefaults(s);
        s.put(Prefs.AMOLED, true);   // changed after the snapshot, absent in it
        PrefsModel.importMap(snap, s, true);
        assertNull(s.get(Prefs.AMOLED));
        assertEquals(PrefsModel.exportMap(v151Store()), PrefsModel.exportMap(s));
    }

    /** 228: every old (sleepRemember, sleepDefault) pair reads to a choice that writes back the same meaning. */
    @Test public void sleepDefaultMappingAllOldValues() {
        int[] modes = {15, 30, 45, 60, 90, -1, -102, -103, -105};
        for (boolean remember : new boolean[]{true, false}) {
            for (int m : modes) {
                int choice = PrefsModel.sleepChoice(remember, m);
                assertEquals(remember ? PrefsModel.SLEEP_CHOICE_LAST : m, choice);
                PrefsModel.MapStore s = new PrefsModel.MapStore();
                s.put(Prefs.SLEEP_REMEMBER, remember);
                s.put(Prefs.SLEEP_DEFAULT, m);
                PrefsModel.writeSleepChoice(s, choice);
                assertEquals(remember, s.get(Prefs.SLEEP_REMEMBER));
                assertEquals(m, s.get(Prefs.SLEEP_DEFAULT));
            }
        }
        PrefsModel.MapStore s = new PrefsModel.MapStore();
        PrefsModel.writeSleepChoice(s, 45);
        assertEquals(false, s.get(Prefs.SLEEP_REMEMBER));
        assertEquals(45, s.get(Prefs.SLEEP_DEFAULT));
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
        assertEquals("上一个 · 播放 · 下一个 · 睡眠 · 稍后观看", PrefsModel.notifLayout(false, true));
        assertEquals("快退 10 秒 · 播放 · 快进 30 秒 · 睡眠", PrefsModel.notifLayout(true, false));
    }

    @Test public void booleanDefaultsUnchanged() {
        String[] off = {Prefs.NORMALIZE, Prefs.REMEMBER_RATE, Prefs.SLEEP_CALM, Prefs.NOTIF_SEEK, Prefs.HIDE_LIVE, Prefs.HIDE_SHELVES,
                Prefs.SUBS_ONLY_VIDEOS, Prefs.UPDATE_MIRROR, Prefs.AMOLED, Prefs.COMPACT, Prefs.CARD_STYLE, Prefs.DIM_THUMBS};
        java.util.List<String> offL = java.util.Arrays.asList(off);
        for (String k : PrefsModel.BOOL_KEYS) assertEquals(k, !offL.contains(k), PrefsModel.defBool(k));
        assertEquals(30, PrefsModel.defInt(Prefs.SLEEP_DEFAULT));
        assertEquals(60, PrefsModel.defInt(Prefs.SLEEP_FADE));
        assertEquals(80, PrefsModel.defInt(Prefs.SEEN_MIN));
    }
}
