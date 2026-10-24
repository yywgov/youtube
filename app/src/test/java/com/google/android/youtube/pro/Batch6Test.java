package com.google.android.youtube.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** v1.8.0 (batch 6): plain-Java logic behind the new behaviour. */
public class Batch6Test {

    /** 308: minutes, and in the last minute seconds in steps of 5 (never 0 while running). */
    @Test public void sleepBadge() {
        assertEquals("睡眠 · 20 分", Logic.sleepBadge(19 * 60_000L + 1));
        assertEquals("睡眠 · 2 分", Logic.sleepBadge(60_001L));
        assertEquals("睡眠 · 1 分", Logic.sleepBadge(60_000L));
        assertEquals("睡眠 · 45 秒", Logic.sleepBadge(44_200L));
        assertEquals("睡眠 · 5 秒", Logic.sleepBadge(800L));
        assertEquals("睡眠 · 5 秒", Logic.sleepBadge(0L));
    }

    /** 276: one night's line. */
    @Test public void nightSummary() {
        assertEquals("播放 3 小时 10 分，耗电 9%", Logic.nightSummary(190, 9));
        assertEquals("播放 45 分钟，耗电 0%", Logic.nightSummary(45, -2));
    }

    /** 303 / 305 / 306 / 313 / 315 / 316 / 327 / 333: new keys, defaults keep the old behaviour, values checked. */
    @Test public void newSettings() {
        for (String k : new String[]{Prefs.HIDE_WATCHED_RELATED, Prefs.HIDE_SEARCH_MIX, Prefs.LIGHT_REFRESH, Prefs.SLEEP_HOLD,
                Prefs.SLEEP_MODE_BLACK, Prefs.LIST_END_NEXT, Prefs.SLEEP_STRICT}) {
            assertTrue(k, PrefsModel.isBool(k));
            assertFalse(k + " is off by default", PrefsModel.defBool(k));
        }
        assertTrue(PrefsModel.isInt(Prefs.EXTEND_STEP));
        assertEquals(15, PrefsModel.defInt(Prefs.EXTEND_STEP));
        assertEquals(30, PrefsModel.validate(Prefs.EXTEND_STEP, 30));
        assertNull(PrefsModel.validate(Prefs.EXTEND_STEP, 20));
        assertNull(PrefsModel.validate(Prefs.SLEEP_HOLD, "true"));
        // a backup made by this version restores into a clean store and carries the new keys
        PrefsModel.MapStore s = new PrefsModel.MapStore();
        s.put(Prefs.EXTEND_STEP, 30);
        s.put(Prefs.SLEEP_STRICT, true);
        Map<String, Object> out = PrefsModel.exportMap(s);
        Map<String, Object> b = new LinkedHashMap<>(out);
        b.put("app", "YiWei");
        b.put("v", 2);
        PrefsModel.MapStore t = new PrefsModel.MapStore();
        PrefsModel.ImportResult r = PrefsModel.importMap(b, t, false);
        assertEquals(2, r.applied);
        assertEquals(30, t.get(Prefs.EXTEND_STEP));
        // an older backup without them leaves them at their defaults (absent)
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("app", "YiWei");
        old.put(Prefs.SPONSOR_BLOCK, false);
        PrefsModel.MapStore u = new PrefsModel.MapStore();
        PrefsModel.importMap(old, u, false);
        assertNull(u.get(Prefs.EXTEND_STEP));
    }
}
