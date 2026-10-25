package com.google.android.youtube.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Iteration round 1 (v1.9.0): link helpers moved out of MainActivity (437). */
public class Round1Test {
    @Test public void trackingParams() {
        assertTrue(Logic.isTrackingParam("si"));
        assertTrue(Logic.isTrackingParam("SI"));
        assertTrue(Logic.isTrackingParam("feature"));
        assertTrue(Logic.isTrackingParam("pp"));
        assertTrue(Logic.isTrackingParam("utm_source"));
        assertTrue(Logic.isTrackingParam("fbclid"));
        assertTrue(Logic.isTrackingParam("gclid"));
        assertFalse(Logic.isTrackingParam("v"));
        assertFalse(Logic.isTrackingParam("t"));
        assertFalse(Logic.isTrackingParam("list"));
        assertFalse(Logic.isTrackingParam("index"));
        assertFalse(Logic.isTrackingParam(null));
    }

    @Test public void sharedText() {
        assertEquals("https://youtu.be/abcdefghijk?si=x", Logic.findYouTubeLink("看看这个 https://youtu.be/abcdefghijk?si=x 好看"));
        assertEquals("https://m.youtube.com/watch?v=abc", Logic.findYouTubeLink("https://m.youtube.com/watch?v=abc"));
        assertEquals("https://www.youtube.com/@chan", Logic.findYouTubeLink("chan: https://www.youtube.com/@chan"));
        assertNull(Logic.findYouTubeLink("https://example.com/watch?v=abc"));
        assertNull(Logic.findYouTubeLink("no link"));
        assertNull(Logic.findYouTubeLink(null));
    }
}
