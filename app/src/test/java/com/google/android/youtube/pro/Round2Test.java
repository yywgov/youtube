package com.google.android.youtube.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/** Iteration round 2 (v1.10.0): merged settings rows (455/459) and old backups after the personal-use trim. */
public class Round2Test {
    @Test public void fontChoiceMapsBothKeys() {
        assertEquals(0, PrefsModel.fontChoice(false, false));
        assertEquals(0, PrefsModel.fontChoice(false, true));
        assertEquals(1, PrefsModel.fontChoice(true, false));
        assertEquals(2, PrefsModel.fontChoice(true, true));
        PrefsModel.MapStore s = new PrefsModel.MapStore();
        PrefsModel.writeFontChoice(s, 2);
        assertEquals(true, s.get(Prefs.FONT_FOLLOW));
        assertEquals(true, s.get(Prefs.FONT_LARGE));
        PrefsModel.writeFontChoice(s, 0);
        assertEquals(false, s.get(Prefs.FONT_FOLLOW));
        assertEquals("fixed keeps the large flag for later", true, s.get(Prefs.FONT_LARGE));
        PrefsModel.writeFontChoice(s, 1);
        assertEquals(true, s.get(Prefs.FONT_FOLLOW));
        assertEquals(false, s.get(Prefs.FONT_LARGE));
    }

    @Test public void searchTidyWritesBoth() {
        assertTrue(PrefsModel.searchTidy(true, false));   // the default: extras hidden, mixes shown
        assertTrue(PrefsModel.searchTidy(false, true));
        assertFalse(PrefsModel.searchTidy(false, false));
        PrefsModel.MapStore s = new PrefsModel.MapStore();
        PrefsModel.writeSearchTidy(s, true);
        assertEquals(true, s.get(Prefs.HIDE_SEARCH_EXTRAS));
        assertEquals(true, s.get(Prefs.HIDE_SEARCH_MIX));
        PrefsModel.writeSearchTidy(s, false);
        assertEquals(false, s.get(Prefs.HIDE_SEARCH_EXTRAS));
        assertEquals(false, s.get(Prefs.HIDE_SEARCH_MIX));
    }

    /** A v1.9 backup carrying keys of removed features still imports in full: nothing ignored, nothing lost. */
    @Test public void oldBackupWithRemovedFeaturesImports() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("app", "YiWei");
        b.put("v", 2);
        b.put(Prefs.ORIENT_LOCK, 2);
        b.put(Prefs.ORIENT_REMEMBER, true);
        b.put(Prefs.LANDSCAPE_LAYOUT, true);
        b.put(Prefs.SEARCH_FOCUS, false);
        b.put(Prefs.RESUME_LOCAL, false);
        b.put(Prefs.FONT_LARGE, true);
        b.put(Prefs.HIDE_SEARCH_MIX, true);
        b.put(Prefs.ACCENT, "#9C6BFF");
        PrefsModel.MapStore s = new PrefsModel.MapStore();
        PrefsModel.ImportResult r = PrefsModel.importMap(b, s, false);
        assertFalse(r.notBackup);
        assertEquals(8, r.applied);
        assertEquals(0, r.ignored);
        assertEquals(2, s.get(Prefs.ORIENT_LOCK));
        assertEquals("#9C6BFF", s.get(Prefs.ACCENT));
        // and the export of such a store still carries them, so going back a version loses nothing
        Map<String, Object> out = PrefsModel.exportMap(s);
        assertEquals(true, out.get(Prefs.LANDSCAPE_LAYOUT));
        assertEquals(false, out.get(Prefs.SEARCH_FOCUS));
    }

    @Test public void migrationStillIdempotent() {
        PrefsModel.MapStore s = new PrefsModel.MapStore();
        s.put(Prefs.ORIENT_LOCK, 1);
        PrefsModel.migrate(s);
        assertFalse(PrefsModel.migrate(s));
        assertEquals("old values are left alone", 1, s.get(Prefs.ORIENT_LOCK));
    }
}
