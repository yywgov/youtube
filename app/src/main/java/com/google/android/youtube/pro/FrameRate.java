package com.google.android.youtube.pro;

import android.app.Activity;
import android.os.Build;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Display refresh rate (moved out of MainActivity, 436; behaviour unchanged):
 * the highest-rate display mode at the current resolution (120 Hz LTPO panels, ColorOS),
 * Android 15+ high frame rate only while touching (275) and samples of the rate while
 * scrolling to tell whether the system holds the app at 60 Hz (391).
 */
final class FrameRate {
    private final Activity a;
    private final Supplier<View> web;
    private final BooleanSupplier fullscreen;
    private boolean frameHigh = false;
    private long lastSample = 0;
    // samples survive a recreated activity (the "limited" hint on the about page)
    private static int samples = 0, low = 0;
    private static float max = 0;

    FrameRate(Activity a, Supplier<View> web, BooleanSupplier fullscreen) {
        this.a = a;
        this.web = web;
        this.fullscreen = fullscreen;
    }

    @SuppressWarnings("deprecation")
    private Display display() {
        return Build.VERSION.SDK_INT >= 30 ? a.getDisplay() : a.getWindowManager().getDefaultDisplay();
    }

    /**
     * Ask for the display mode with the highest refresh rate at the current resolution.
     * LTPO panels (and ColorOS/MIUI) otherwise often keep WebView apps at 60 Hz.
     */
    void requestHigh() {
        try {
            Display d = display();
            if (d == null) return;
            Display.Mode cur = d.getMode();
            Display.Mode best = cur;
            for (Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() == cur.getPhysicalWidth()
                        && m.getPhysicalHeight() == cur.getPhysicalHeight()
                        && m.getRefreshRate() > best.getRefreshRate() + 0.5f) {
                    best = m;
                }
            }
            WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            if (lp.preferredDisplayModeId != best.getModeId() || lp.preferredRefreshRate != best.getRefreshRate()) {
                lp.preferredDisplayModeId = best.getModeId();
                lp.preferredRefreshRate = best.getRefreshRate();
                a.getWindow().setAttributes(lp);
            }
            if (Build.VERSION.SDK_INT >= 35) {
                a.getWindow().setFrameRatePowerSavingsBalanced(false);
                a.getWindow().setFrameRateBoostOnTouchEnabled(true);
                setWeb(true);
            }
        } catch (Throwable ignored) {}
    }

    /** Touch over (275): back to the normal rate, except under fullscreen video. */
    void idle() {
        if (fullscreen.getAsBoolean()) return;   // fullscreen video keeps its rate
        setWeb(false);
    }

    /** Android 15+ (275): ask for the high frame rate only while touching / scrolling. */
    void setWeb(boolean high) {
        View w = web.get();
        if (Build.VERSION.SDK_INT < 35 || w == null || frameHigh == high) return;
        try {
            w.setRequestedFrameRate(high ? View.REQUESTED_FRAME_RATE_CATEGORY_HIGH : View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL);
            frameHigh = high;
        } catch (Throwable ignored) {}
    }

    /** One sample of the rate while scrolling (391), at most every 500 ms and 400 in all. */
    void sample() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSample < 500 || samples >= 400) return;
        lastSample = now;
        try {
            Display d = display();
            if (d == null) return;
            if (max == 0) for (Display.Mode m : d.getSupportedModes()) max = Math.max(max, m.getRefreshRate());
            samples++;
            if (d.getRefreshRate() < max - 5) low++;
        } catch (Throwable ignored) {}
    }

    /** Scrolling has mostly run below the panel's best rate (391). */
    static boolean limited() { return Logic.refreshLimited(samples, low, max); }

    /** "当前 120 Hz · 最高 120 Hz" for the about page and the report. */
    String label() {
        try {
            Display d = display();
            if (d == null) return "未知";
            float best = 0;
            for (Display.Mode m : d.getSupportedModes()) best = Math.max(best, m.getRefreshRate());
            return String.format(java.util.Locale.US, "当前 %.0f Hz · 最高 %.0f Hz", d.getRefreshRate(), Math.max(best, d.getRefreshRate()));
        } catch (Throwable t) {
            return "未知";
        }
    }
}
