package com.google.android.youtube.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LogicTest {
    @Test public void fadeCurve() {
        assertEquals(1, Logic.fadeVolume(60_000, 60_000), 0);
        assertEquals(1, Logic.fadeVolume(90_000, 60_000), 0);
        assertEquals(0, Logic.fadeVolume(0, 60_000), 0);
        assertEquals(0.25, Logic.fadeVolume(30_000, 60_000), 1e-9);
        double prev = 1;
        for (int s = 60; s >= 0; s--) {
            double v = Logic.fadeVolume(s * 1000, 60_000);
            assertTrue(v <= prev);
            prev = v;
        }
    }

    @Test public void versions() {
        assertEquals(1, Logic.compareVersions("1.6.0", "1.5.1"));
        assertEquals(-1, Logic.compareVersions("1.6.0-dev.12", "1.6.0"));
        assertEquals(0, Logic.compareVersions("v1.6.0", "1.6.0"));
        assertEquals(1, Logic.compareVersions("1.10.0", "1.9.9"));
        assertEquals(-1, Logic.compareVersions("1.6", "1.6.1"));
    }

    @Test public void notesWithoutGeneratedEnglishTail() {
        String md = "## 一维视频 1.6.0\n- **设置**合并为 5 组\n\n## What's Changed\n* Bump by @x in https://github.com/a/b/pull/1\n\n**Full Changelog**: https://x";
        String s = Logic.cleanNotes(md);
        assertTrue(s.startsWith("一维视频 1.6.0"));
        assertTrue(s.contains("• 设置合并为 5 组"));
        assertFalse(s.contains("What's Changed"));
        assertFalse(s.contains("Full Changelog"));
        assertFalse(Logic.cleanNotes("中文\n\n**Full Changelog**: https://x").contains("Changelog"));
    }

    @Test public void youTubeHostsOnly() {
        assertTrue(Logic.isYouTubeHost("https://m.youtube.com/"));
        assertTrue(Logic.isYouTubeHost("https://youtube.com/feed"));
        assertFalse(Logic.isYouTubeHost("https://accounts.google.com/signin"));
        assertFalse(Logic.isYouTubeHost("https://evil-youtube.com/"));
        assertFalse(Logic.isYouTubeHost(null));
    }
}
