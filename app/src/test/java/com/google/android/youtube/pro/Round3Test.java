package com.google.android.youtube.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Iteration round 3 (v1.11.0): merged settings rows (474/476). */
public class Round3Test {
    @Test public void sleepDetailLabel() {
        assertEquals("淡出 60 秒 · +15 分钟", PrefsModel.sleepDetailLabel(60, 15, false, false, false));
        assertEquals("淡出 10 秒 · +30 分钟 · 暂停时定时也暂停 · 角标 · 一键睡眠黑屏", PrefsModel.sleepDetailLabel(10, 30, true, true, true));
    }

    @Test public void listStyleLabel() {
        assertEquals("默认", PrefsModel.listStyleLabel(false, false, false));
        assertEquals("紧凑 · 压暗缩略图", PrefsModel.listStyleLabel(true, false, true));
        assertEquals("紧凑 · 统一卡片 · 压暗缩略图", PrefsModel.listStyleLabel(true, true, true));
    }

    @Test public void perfLabel() {
        assertTrue(PrefsModel.perfLabel(true, false).contains("开"));
        assertTrue(PrefsModel.perfLabel(false, true).contains("预渲染"));
    }

    @Test public void removedKeysStayKnown() {
        assertTrue(PrefsModel.isBool(Prefs.NORMALIZE));
        assertTrue(PrefsModel.isBool(Prefs.WIFI_SAVER));
    }
}
