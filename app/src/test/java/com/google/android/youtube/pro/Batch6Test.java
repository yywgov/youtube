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

    /** 303 / 305 / 306 / 313 / 315 / 316 / 327 / 333: new keys, defaults keep the old behaviour, values checked. */
    @Test public void newSettings() {
        for (String k : new String[]{Prefs.HIDE_WATCHED_RELATED, Prefs.HIDE_SEARCH_MIX, Prefs.LIGHT_REFRESH, Prefs.SLEEP_HOLD,
                Prefs.SLEEP_MODE_BLACK, Prefs.LIST_END_NEXT, Prefs.SLEEP_STRICT, Prefs.PRE_RASTER, Prefs.LANDSCAPE_LAYOUT}) {
            assertTrue(k, PrefsModel.isBool(k));
            assertFalse(k + " is off by default", PrefsModel.defBool(k));
        }
        assertTrue(PrefsModel.isInt(Prefs.EXTEND_STEP));
        assertEquals(15, PrefsModel.defInt(Prefs.EXTEND_STEP));
        // stored values are exported as they are (problem report)
        PrefsModel.MapStore s = new PrefsModel.MapStore();
        s.put(Prefs.EXTEND_STEP, 30);
        s.put(Prefs.SLEEP_STRICT, true);
        Map<String, Object> out = PrefsModel.exportMap(s);
        assertEquals(30, out.get(Prefs.EXTEND_STEP));
        assertEquals(true, out.get(Prefs.SLEEP_STRICT));
    }

    /** 260: the streaming rename gives exactly what the whole-page rename gave, however the page is split. */
    @Test public void streamingRenameMatchesWholePage() throws Exception {
        String[] keys = {"adPlacements", "playerAds", "adSlots", "adBreakHeartbeatParams"};
        String page = "<html>中文标题 var a = {\"adPlacements\":[1],\"playerAds\":2}; s='{\\\"adSlots\\\":3}';"
                + " t='\\x22adBreakHeartbeatParams\\x22:4'; keep \"adPlacementsX\" and adSlots and \"adSl\"ots end\"";
        String want = page;
        for (String k : keys) {
            want = want.replace("\"" + k + "\"", "\"" + k + "_ytl\"")
                    .replace("\\\"" + k + "\\\"", "\\\"" + k + "_ytl\\\"")
                    .replace("\\x22" + k + "\\x22", "\\x22" + k + "_ytl\\x22");
        }
        byte[] bytes = page.getBytes("UTF-8");
        java.util.Random rnd = new java.util.Random(7);
        for (int round = 0; round < 200; round++) {
            final int max = 1 + rnd.nextInt(round < 100 ? 7 : 64);
            java.io.InputStream chunky = new java.io.ByteArrayInputStream(bytes) {
                @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(len, max)); }
            };
            KeyRenameStream ks = new KeyRenameStream(chunky, keys);
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 + rnd.nextInt(50)];
            int n;
            while ((n = ks.read(buf, 0, buf.length)) >= 0) o.write(buf, 0, n);
            assertEquals("chunk " + max, want, o.toString("UTF-8"));
            assertEquals(4, ks.renamed());
        }
        // empty page, single-byte reads
        assertEquals(-1, new KeyRenameStream(new java.io.ByteArrayInputStream(new byte[0]), keys).read());
    }

    /** 278: a remote block path is narrow and never touches the player, lists, login or history. */
    @Test public void remoteBlockPaths() {
        assertTrue(Logic.validBlockPath("/api/stats/newads"));
        assertTrue(Logic.validBlockPath("/youtubei/v1/log_interaction"));
        assertFalse(Logic.validBlockPath("/youtubei/v1/player"));
        assertFalse(Logic.validBlockPath("/youtubei/v1/pla"));            // prefix of the player path
        assertFalse(Logic.validBlockPath("/youtubei/v1/"));
        assertFalse(Logic.validBlockPath("/youtubei/v1/next/x"));
        assertFalse(Logic.validBlockPath("/youtubei/v1/browse/edit_playlist"));   // watch later
        assertFalse(Logic.validBlockPath("/api/stats/watchtime"));
        assertFalse(Logic.validBlockPath("/videoplayback"));
        assertFalse(Logic.validBlockPath("/watch"));
        assertFalse(Logic.validBlockPath("/s/player"));
        assertFalse(Logic.validBlockPath("/a"));                           // too short / broad
        assertFalse(Logic.validBlockPath("/api/../x1"));
        assertFalse(Logic.validBlockPath("/api/stats/a?b=1"));
        assertFalse(Logic.validBlockPath(null));
    }

    /** 251 / 414: crash file keeps the last 3; lines older than 30 days go, unstamped lines stay. */
    @Test public void logsHousekeeping() {
        String t = "2026-09-01 10:00:00 [a] old\n2026-10-20 10:00:00 [b] new\n  continued\n2026-10-24 [c] newest";
        assertEquals("2026-10-20 10:00:00 [b] new\n  continued\n2026-10-24 [c] newest", Diag.dropBefore(t, "2026-09-24"));
        assertEquals("", Diag.dropBefore("", "2026-09-24"));
        assertEquals(t, Diag.dropBefore(t, "2000-01-01"));
        String f = "";
        for (int i = 1; i <= 5; i++) f = Diag.addCrash(f, "t" + i + " thread=main\nat X.y(X.java:" + i + ")", 3);
        assertFalse(f.contains("t2 "));
        assertTrue(f.startsWith("=== t3 "));
        assertTrue(f.contains("=== t5 thread=main\nat X.y(X.java:5)"));
    }

}
