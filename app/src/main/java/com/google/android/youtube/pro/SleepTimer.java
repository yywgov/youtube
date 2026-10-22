package com.google.android.youtube.pro;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

/**
 * Sleep timer. Runs on the main looper; while playing the service holds a
 * partial wake lock so the ticks keep coming with the screen off.
 * The last N seconds (setting, 10/30/60) fade the volume out, then playback is paused.
 *
 * Modes: minutes (> 0), {@link #END_OF_VIDEO}, or "after N videos" (encoded as -100 - N).
 */
public final class SleepTimer {
    public static final int OFF = 0;
    public static final int END_OF_VIDEO = -1;
    private static final int COUNT_BASE = -100;
    public static final int[] OPTIONS = {15, 30, 45, 60, 90, END_OF_VIDEO, COUNT_BASE - 2, COUNT_BASE - 3, COUNT_BASE - 5};
    public static final int EXTEND_MIN = 15;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static int mode = OFF;
    private static long endAt = 0L; // elapsedRealtime
    private static boolean fading = false;
    private static int countLeft = 0;
    private static long fadeMs = 60_000L;

    private SleepTimer() {}

    public static int mode() { return mode; }
    public static boolean active() { return mode != OFF; }
    public static boolean timed() { return mode > 0; }

    public static boolean isCount(int m) { return m <= COUNT_BASE - 1; }
    public static int countOf(int m) { return COUNT_BASE - m; }

    /** "end of video" applies to the playing video right now (plain mode, or the last of N). */
    public static boolean stopsAtEndNow() {
        return mode == END_OF_VIDEO || (isCount(mode) && countLeft <= 1);
    }
    public static boolean countRunning() { return isCount(mode) && countLeft > 1; }

    public static String optionLabel(int m) {
        if (m == END_OF_VIDEO) return "当前视频结束时";
        if (isCount(m)) return "播完 " + countOf(m) + " 个视频";
        return m + " 分钟";
    }

    /** Short status text for the notification, or null when off. */
    public static String status() {
        if (mode == OFF) return null;
        if (mode == END_OF_VIDEO) return "睡眠定时：本视频结束";
        if (isCount(mode)) return "睡眠定时：还剩 " + countLeft + " 个视频";
        return "睡眠定时：剩余 " + minutesLeft() + " 分钟";
    }

    /** Text for the corner badge on the player (154), "" when off. */
    public static String badge() {
        if (mode == OFF) return "";
        if (mode == END_OF_VIDEO) return "睡眠 · 本视频后";
        if (isCount(mode)) return "睡眠 · 还剩 " + countLeft + " 个";
        return "睡眠 · " + minutesLeft() + " 分";
    }

    private static long minutesLeft() {
        long left = Math.max(0, endAt - SystemClock.elapsedRealtime());
        return (left + 59_999L) / 60_000L;
    }

    public static void start(Context c, int minutes) {
        reset();
        if (c != null) fadeMs = Prefs.sleepFade(c) * 1000L;
        mode = minutes;
        if (minutes == END_OF_VIDEO) {
            MainActivity.js("ytl.stopAtEnd(true)");
        } else if (isCount(minutes)) {
            countLeft = Math.max(1, countOf(minutes));
            if (countLeft <= 1) MainActivity.js("ytl.stopAtEnd(true)");
            else MainActivity.js("ytl.countMode(true)");
        } else {
            endAt = SystemClock.elapsedRealtime() + minutes * 60_000L;
            H.post(TICK);
        }
        // one-tap restart next time (15)
        if (c != null && Prefs.sleepRemember(c)) Prefs.put(c, Prefs.SLEEP_DEFAULT, minutes);
        toast(c, "睡眠定时：" + optionLabel(minutes));
        pushState();
    }

    public static void cancel(Context c) {
        boolean was = mode != OFF;
        reset();
        MainActivity.js("ytl.stopAtEnd(false);ytl.countMode&&ytl.countMode(false);ytl.restoreVolume()");
        if (was && c != null) toast(c, "已关闭睡眠定时");
        pushState();
    }

    /** Notification "+15 min" (14): only for a running minutes timer; undoes a fade in progress. */
    public static void extend(Context c) {
        if (mode <= 0) return;
        long now = SystemClock.elapsedRealtime();
        endAt = Math.max(endAt, now) + EXTEND_MIN * 60_000L;
        if (fading) {
            fading = false;
            MainActivity.js("ytl.restoreVolume()");
        }
        H.removeCallbacks(TICK);
        H.post(TICK);
        toast(c, "睡眠定时：+" + EXTEND_MIN + " 分钟（剩余 " + minutesLeft() + " 分钟）");
        pushState();
    }

    /** Notification / media-control button: off -> default -> next option ... -> off. */
    public static void cycle(Context c) {
        if (mode == OFF) {
            start(c, Prefs.sleepDefault(c));
            return;
        }
        int idx = -1;
        for (int i = 0; i < OPTIONS.length; i++) if (OPTIONS[i] == mode) idx = i;
        if (idx < 0 || idx == OPTIONS.length - 1) cancel(c);
        else start(c, OPTIONS[idx + 1]);
    }

    /** Called from JS when "end of current video" mode paused playback. */
    public static void onEndOfVideoFired() {
        boolean was = mode != OFF;
        reset();
        MainActivity.js("ytl.countMode&&ytl.countMode(false)");
        pushState();
        if (was) PlaybackService.onSleepEnded();
    }

    /** "After N videos": the page reports each video that played to its end (16). */
    public static void onVideoEnded() {
        if (!isCount(mode) || countLeft <= 1) return;
        countLeft--;
        if (countLeft <= 1) MainActivity.js("ytl.countMode(false);ytl.stopAtEndNext()");
        pushState();
    }

    private static void reset() {
        H.removeCallbacks(TICK);
        mode = OFF;
        endAt = 0L;
        fading = false;
        countLeft = 0;
    }

    /** Notification, page badge and "sleeping" flag follow the timer. */
    private static void pushState() {
        PlaybackService.refresh();
        MainActivity.js("ytl.sleepState&&ytl.sleepState(" + (mode != OFF) + ");ytl.badge&&ytl.badge("
                + org.json.JSONObject.quote(badge()) + ")");
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (mode <= 0) return;
            long left = endAt - SystemClock.elapsedRealtime();
            if (left <= 0) {
                MainActivity.js("ytl.sleepPause()");
                reset();
                pushState();
                PlaybackService.onSleepEnded();
                return;
            }
            if (left <= fadeMs) {
                fading = true;
                double vol = (double) left / fadeMs;
                MainActivity.js("ytl.setVolume(" + String.format(java.util.Locale.US, "%.3f", vol) + ")");
                H.postDelayed(this, 1000);
            } else {
                // refresh the notification once a minute, wake up exactly when the fade starts
                long untilFade = left - fadeMs;
                long untilMinute = left % 60_000L;
                if (untilMinute < 1000) untilMinute = 60_000L;
                H.postDelayed(this, Math.max(1000, Math.min(untilFade, untilMinute)));
            }
            pushState();
        }
    };

    private static void toast(Context c, String s) {
        if (c == null) return;
        Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }
}
