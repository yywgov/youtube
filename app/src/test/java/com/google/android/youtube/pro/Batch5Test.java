package com.google.android.youtube.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** v1.7.0 (batch 5): plain-Java logic behind the new behaviour. */
public class Batch5Test {

    /** 410: the SponsorBlock lookup sends 4 hex digits of sha256(videoId), never the id. */
    @Test public void sponsorBlockPrefix() {
        String p = Logic.sbPrefix("dQw4w9WgXcQ");
        assertEquals(4, p.length());
        assertTrue(p.matches("[0-9a-f]{4}"));
        assertEquals(p, Logic.sbPrefix("dQw4w9WgXcQ"));
        // sha256("abc") = ba7816bf…
        assertEquals("ba78", Logic.sbPrefix("abc"));
    }

    /** 409: app-acting bridge calls are refused only on a known non-YouTube page. */
    @Test public void bridgeHosts() {
        assertTrue(Logic.bridgeAllowed("m.youtube.com"));
        assertTrue(Logic.bridgeAllowed("youtube.com"));
        assertTrue(Logic.bridgeAllowed(""));          // not known yet: never refuse a YouTube page by mistake
        assertTrue(Logic.bridgeAllowed(null));
        assertFalse(Logic.bridgeAllowed("accounts.google.com"));
        assertFalse(Logic.bridgeAllowed("www.google.com"));
        assertFalse(Logic.bridgeAllowed("youtube.com.evil.example"));
        assertEquals("accounts.google.com", Logic.hostOf("https://accounts.google.com/signin/v2?x=1"));
        assertEquals("", Logic.hostOf("about:blank"));
    }

    /** 411: microphone only for YouTube pages, matched by host. */
    @Test public void micOrigin() {
        assertTrue(Logic.micOriginOk("https://m.youtube.com/"));
        assertTrue(Logic.micOriginOk("https://www.youtube.com"));
        assertFalse(Logic.micOriginOk("https://notyoutube.com/"));
        assertFalse(Logic.micOriginOk("https://youtube.com.example.org/"));
        assertFalse(Logic.micOriginOk("https://evil.example/?youtube.com"));
    }

    /** 399: headings are found so they can be shown in bold; list bullets stay. */
    @Test public void releaseNoteHeadings() {
        String md = "## 新功能\n- 无操作保护\n- 夜间时段\n### **修复**\n* 断网提示\n## What's Changed\n* x by y";
        assertEquals(java.util.Arrays.asList("新功能", "修复"), Logic.noteHeadings(md));
        String clean = Logic.cleanNotes(md);
        assertTrue(clean.contains("新功能\n• 无操作保护\n• 夜间时段"));
        assertFalse(clean.contains("What's Changed"));
    }

    /** 391: "held back" only with enough samples, mostly low, on a > 90 Hz panel. */
    @Test public void refreshRateHeldBack() {
        assertTrue(Logic.refreshLimited(20, 18, 120f));
        assertFalse(Logic.refreshLimited(20, 10, 120f));
        assertFalse(Logic.refreshLimited(5, 5, 120f));
        assertFalse(Logic.refreshLimited(50, 50, 60f));
    }

    /** 413: the report carries the block lists only as counts. */
    @Test public void reportRedaction() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", "YiWei");
        m.put(Prefs.BLOCK_CHANNELS, "@secret|My Secret\nOther");
        m.put(Prefs.BLOCK_WORDS, "spoiler\n\n");
        m.put(Prefs.QUALITY, "720");
        Map<String, Object> r = PrefsModel.redactForReport(m);
        assertEquals("720", r.get(Prefs.QUALITY));
        assertTrue(String.valueOf(r.get(Prefs.BLOCK_CHANNELS)).startsWith("2 条"));
        assertTrue(String.valueOf(r.get(Prefs.BLOCK_WORDS)).startsWith("1 条"));
        assertFalse(r.toString().contains("secret"));
        assertFalse(r.toString().contains("spoiler"));
        assertEquals("@secret|My Secret\nOther", m.get(Prefs.BLOCK_CHANNELS));   // original untouched
    }

    /** Batch 5 keys: absent = default (validation went with backup / restore in v1.15). */
    @Test public void newSettingsDefault() {
        assertEquals(22, PrefsModel.defInt(Prefs.NIGHT_START));
        assertEquals(5, PrefsModel.defInt(Prefs.NIGHT_END));
        assertEquals(0, PrefsModel.defInt(Prefs.ORIENT_LOCK));
        assertTrue(PrefsModel.defBool(Prefs.IDLE_GUARD));
        assertFalse(PrefsModel.defBool(Prefs.WIFI_SAVER));
    }

    /** Migration stays idempotent with the new keys around. */
    @Test public void migrationIgnoresNewKeys() {
        PrefsModel.MapStore s = new PrefsModel.MapStore();
        s.put(Prefs.NIGHT_START, 21);
        s.put(Prefs.IDLE_GUARD, false);
        assertTrue(PrefsModel.migrate(s));
        assertFalse(PrefsModel.migrate(s));
        assertEquals(21, s.get(Prefs.NIGHT_START));
        assertEquals(false, s.get(Prefs.IDLE_GUARD));
    }
}
