package com.google.android.youtube.pro;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** v1.23+ redesign: plain-Java logic behind the new look. */
public class RedesignTest {

    /** Brand red: nothing stored or the old brand blue means the default; an older pick stays. */
    @Test public void defaultAccent() {
        assertTrue(Logic.isDefaultAccent(""));
        assertTrue(Logic.isDefaultAccent(null));
        assertTrue(Logic.isDefaultAccent("#2F6BFF"));
        assertTrue(Logic.isDefaultAccent("#2f6bff"));
        assertFalse(Logic.isDefaultAccent("#00BFA5"));
        assertFalse(Logic.isDefaultAccent("#3EA6FF"));
    }

    /** Sheet drag: past 35 % or a fast downward fling closes, otherwise it springs back. */
    @Test public void sheetDismiss() {
        assertTrue(Logic.sheetShouldDismiss(400, 1000, 0, 3));
        assertFalse(Logic.sheetShouldDismiss(300, 1000, 0, 3));
        assertTrue(Logic.sheetShouldDismiss(50, 1000, 3500, 3));
        assertFalse(Logic.sheetShouldDismiss(50, 1000, 2500, 3));
        assertFalse(Logic.sheetShouldDismiss(-20, 1000, 5000, 3));
        assertFalse(Logic.sheetShouldDismiss(10, 0, 0, 3));
    }
}
