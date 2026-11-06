package com.google.android.youtube.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * v1.15.0: sleep timer, backup / restore, keep-alive guidance, "lowest quality in the background",
 * the resume prompt, the original-audio switch and the update mirrors were removed. Schema 3 deletes
 * only the state those features left behind; every setting key keeps its stored value.
 */
public class V115Test {

    private static PrefsModel.MapStore v114Store() {
        PrefsModel.MapStore s = new PrefsModel.MapStore();
        s.put(PrefsModel.PREFS_SCHEMA, 2);
        // settings of removed features: still there afterwards, never read
        s.put(Prefs.LOW_QUALITY_BG, true);
        s.put(Prefs.SLEEP_DEFAULT, -1);
        s.put(Prefs.SLEEP_FADE, 30);
        s.put(Prefs.SLEEP_REMEMBER, false);
        s.put(Prefs.SLEEP_BADGE, true);
        s.put(Prefs.NIGHT_PROMPT, true);
        s.put(Prefs.IDLE_GUARD, true);
        s.put(Prefs.NIGHT_START, 23);
        s.put(Prefs.EXTEND_STEP, 30);
        s.put(Prefs.SLEEP_CALM, true);
        s.put(Prefs.SLEEP_STRICT, true);
        s.put(Prefs.ORIG_AUDIO, false);
        s.put(Prefs.UPDATE_MIRROR, true);
        s.put(Prefs.RESUME_PROMPT, true);
        // settings that stay
        s.put(Prefs.QUALITY, "1080");
        s.put(Prefs.AMOLED, true);
        s.put(Prefs.BLOCK_CHANNELS, "@x|X");
        // state of removed features: goes
        s.put(PrefsModel.SNAPSHOT, "{\"app\":\"YiWei\"}");
        s.put(Prefs.SLEEP_STATE, "30|123|0|456");
        s.put(Prefs.NIGHT_SHOWN, "2026-300");
        s.put(Prefs.SLEEP_BATTERY, "{}");
        s.put(Prefs.MIRROR_OK, "https://ghfast.top/");
        // state that stays
        s.put(Prefs.LAST_VIDEO, "abcdefghijk");
        s.put(Prefs.FGS_DENIED_AT, 5L);
        return s;
    }

    @Test public void schema3DropsOnlyRemovedState() {
        assertEquals(3, PrefsModel.SCHEMA);
        PrefsModel.MapStore s = v114Store();
        Map<String, Object> before = new HashMap<>(s.map);
        assertTrue(PrefsModel.migrate(s));
        assertEquals(3, s.get(PrefsModel.PREFS_SCHEMA));
        for (String k : PrefsModel.REMOVED_STATE) assertNull(k, s.get(k));
        for (Map.Entry<String, Object> e : before.entrySet()) {
            if (java.util.Arrays.asList(PrefsModel.REMOVED_STATE).contains(e.getKey()) || PrefsModel.PREFS_SCHEMA.equals(e.getKey())) continue;
            assertEquals(e.getKey(), e.getValue(), s.get(e.getKey()));
        }
        Map<String, Object> once = new HashMap<>(s.map);
        assertFalse("idempotent", PrefsModel.migrate(s));
        assertEquals(once, s.map);
    }

    @Test public void schema1And2AndFreshInstallAllEndAt3() {
        for (Object from : new Object[]{null, 1, 2}) {
            PrefsModel.MapStore s = new PrefsModel.MapStore();
            if (from != null) s.put(PrefsModel.PREFS_SCHEMA, from);
            s.put(PrefsModel.OLD_COMPACT_CHOICE, "compact");
            s.put(PrefsModel.SNAPSHOT, "x");
            assertTrue(PrefsModel.migrate(s));
            assertEquals(3, s.get(PrefsModel.PREFS_SCHEMA));
            assertNull(s.get(PrefsModel.SNAPSHOT));
            if (from == null || (Integer) from < 2) assertNull(s.get(PrefsModel.OLD_COMPACT_CHOICE));
        }
        PrefsModel.MapStore newer = new PrefsModel.MapStore();
        newer.put(PrefsModel.PREFS_SCHEMA, 4);
        newer.put(PrefsModel.SNAPSHOT, "kept by a newer version");
        assertFalse(PrefsModel.migrate(newer));
        assertEquals("kept by a newer version", newer.get(PrefsModel.SNAPSHOT));
    }

    @Test public void removedSettingsStayKnownKeys() {
        for (String k : new String[]{Prefs.LOW_QUALITY_BG, Prefs.SLEEP_REMEMBER, Prefs.SLEEP_BADGE, Prefs.NIGHT_PROMPT, Prefs.SLEEP_CALM,
                Prefs.IDLE_GUARD, Prefs.SLEEP_HOLD, Prefs.SLEEP_MODE_BLACK, Prefs.SLEEP_STRICT, Prefs.ORIG_AUDIO, Prefs.UPDATE_MIRROR, Prefs.RESUME_PROMPT}) {
            assertTrue(k, PrefsModel.isBool(k));
        }
        for (String k : new String[]{Prefs.SLEEP_DEFAULT, Prefs.SLEEP_FADE, Prefs.NIGHT_START, Prefs.NIGHT_END, Prefs.EXTEND_STEP}) {
            assertTrue(k, PrefsModel.isInt(k));
        }
        // the problem report still lists them as stored
        Map<String, Object> out = PrefsModel.exportMap(v114Store());
        assertEquals(true, out.get(Prefs.LOW_QUALITY_BG));
        assertFalse(out.containsKey(PrefsModel.SNAPSHOT));
        assertFalse(out.containsKey(Prefs.SLEEP_STATE));
    }

    @Test public void notificationSummaryHasNoSleepKey() {
        assertFalse(PrefsModel.notifLayout(false, true).contains("睡眠"));
        assertFalse(PrefsModel.notifLayout(true, false).contains("睡眠"));
    }
}
