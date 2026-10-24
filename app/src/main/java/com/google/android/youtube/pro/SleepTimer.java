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
    /** Default "+N min" step; the setting can make it 10 or 30 (303). */
    public static final int EXTEND_MIN = 15;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static int mode = OFF;
    private static long endAt = 0L; // elapsedRealtime
    private static boolean fading = false;
    private static int countLeft = 0;
    private static long fadeMs = 60_000L;
    // notification button undo (300): what the button just switched off, and until when it can come back
    private static final long UNDO_MS = 5_000L;
    private static int undoMode = OFF;
    private static long undoLeftMs = 0L;
    private static int undoCount = 0;
    private static long undoUntil = 0L;
    private static Context appCtx;
    private static String lastPushed = "";
    // paused playback holds a minutes timer when the setting is on (305)
    private static boolean held = false;
    private static long heldLeft = 0L;
    /** Count / end-of-video timers restored after a restart only if saved this recently (244). */
    private static final long RESTORE_MAX_AGE_MS = 2 * 3600_000L;

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
        if (held) return "睡眠定时：已随暂停停住（剩余 " + minutesLeft() + " 分钟）";
        return "睡眠定时：剩余 " + minutesLeft() + " 分钟";
    }

    /** "+N min" step from the settings (303): 10 / 15 / 30. */
    public static int extendMin(Context c) {
        Context x = c != null ? c : appCtx;
        if (x == null) return EXTEND_MIN;
        int v = Prefs.num(x, Prefs.EXTEND_STEP);
        return v == 10 || v == 30 ? v : EXTEND_MIN;
    }

    /** Text for the corner badge on the player (154), "" when off. */
    public static String badge() {
        if (mode == OFF) return "";
        if (mode == END_OF_VIDEO) return "睡眠 · 本视频后";
        if (isCount(mode)) return "睡眠 · 还剩 " + countLeft + " 个";
        if (held) return "睡眠 · 暂停中 " + minutesLeft() + " 分";
        return Logic.sleepBadge(leftMs());   // seconds in the last minute (308)
    }

    private static long leftMs() {
        return held ? heldLeft : Math.max(0, endAt - SystemClock.elapsedRealtime());
    }

    private static long minutesLeft() {
        return (leftMs() + 59_999L) / 60_000L;
    }

    /**
     * Playback paused / resumed (305): with "pause holds the timer" on, a minutes timer stops counting
     * while nothing plays and goes on from the same point when playback does. Off: as before.
     */
    public static void onPlayback(Context c, boolean playing) {
        if (mode <= 0 || c == null) return;
        boolean hold = Prefs.on(c, Prefs.SLEEP_HOLD);
        long now = SystemClock.elapsedRealtime();
        if (!playing && hold && !held) {
            heldLeft = Math.max(1000L, endAt - now);
            held = true;
            H.removeCallbacks(TICK);
            persist();
            pushState();
        } else if (held && (playing || !hold)) {
            endAt = now + heldLeft;
            held = false;
            H.removeCallbacks(TICK);
            H.post(TICK);
            persist();
            pushState();
        }
    }

    public static boolean held() { return held; }

    public static void start(Context c, int minutes) {
        startInternal(c, minutes, -1L, -1, false);
    }

    /** Started by the app itself (inactivity guard, 277): not remembered as the default, own message. */
    public static void startAuto(Context c, int minutes, String message) {
        startInternal(c, minutes, -1L, -1, true);
        toast(c, message);
    }

    /** Start (or bring back, 244/300) a timer; leftMs / count >= 0 resume a previous one. */
    private static void startInternal(Context c, int minutes, long leftMs, int count, boolean quiet) {
        reset();
        if (c != null) appCtx = c.getApplicationContext();
        if (c != null) fadeMs = Prefs.sleepFade(c) * 1000L;
        mode = minutes;
        if (minutes == END_OF_VIDEO) {
            MainActivity.js("ytl.stopAtEnd(true)");
        } else if (isCount(minutes)) {
            countLeft = count > 0 ? count : Math.max(1, countOf(minutes));
            if (countLeft <= 1) MainActivity.js("ytl.stopAtEnd(true)");
            else MainActivity.js("ytl.countMode(true)");
        } else {
            endAt = SystemClock.elapsedRealtime() + (leftMs > 0 ? leftMs : minutes * 60_000L);
            H.post(TICK);
        }
        if (!quiet) {
            // one-tap restart next time (15); the settings row decides whether this is on (228)
            if (c != null && Prefs.sleepRemember(c)) Prefs.put(c, Prefs.SLEEP_DEFAULT, minutes);
            toast(c, "睡眠定时：" + optionLabel(minutes));
            MainActivity.hapticNow(Ui.HAPTIC_CONFIRM);   // (372)
        }
        if (leftMs < 0 && count < 0) NightLog.start(appCtx);   // a new night, not a restored timer (276)
        persist();
        pushState();
    }

    // ---------- persistence (244): a restarted process keeps the timer ----------

    private static void persist() {
        Context c = appCtx;
        if (c == null) return;
        try {
            if (mode == OFF) {
                Prefs.get(c).edit().remove(Prefs.SLEEP_STATE).apply();
                return;
            }
            long now = System.currentTimeMillis();
            long endWall = mode > 0 ? now + leftMs() : 0;
            Prefs.put(c, Prefs.SLEEP_STATE, mode + "|" + endWall + "|" + countLeft + "|" + now);
        } catch (Throwable ignored) {}
    }

    /** Process start: bring back a timer the system killed with the app (only if it has not run out). */
    public static void restore(Context c) {
        if (c == null || mode != OFF) return;
        appCtx = c.getApplicationContext();
        String s = Prefs.str(c, Prefs.SLEEP_STATE, "");
        if (s.isEmpty()) return;
        try {
            String[] p = s.split("\\|");
            int m = Integer.parseInt(p[0]);
            long endWall = Long.parseLong(p[1]);
            int count = Integer.parseInt(p[2]);
            long savedAt = Long.parseLong(p[3]);
            long now = System.currentTimeMillis();
            boolean ok;
            long left = 0;
            if (m > 0) {
                left = endWall - now;
                ok = left > 5_000L && left <= m * 60_000L + 60_000L;
            } else {
                ok = (m == END_OF_VIDEO || isCount(m)) && now - savedAt >= 0 && now - savedAt < RESTORE_MAX_AGE_MS;
            }
            if (!ok || !PrefsModel.validSleepMode(m)) {
                Prefs.get(c).edit().remove(Prefs.SLEEP_STATE).apply();
                return;
            }
            startInternal(c, m, left, count, true);
            Diag.log("sleep", "restored " + optionLabel(m) + (m > 0 ? " left=" + (left / 1000) + "s" : " count=" + count));
            toast(c, "已恢复睡眠定时：" + (m > 0 ? "剩余 " + minutesLeft() + " 分钟" : optionLabel(m)));
        } catch (Throwable t) {
            Prefs.get(c).edit().remove(Prefs.SLEEP_STATE).apply();
        }
    }

    public static void cancel(Context c) {
        boolean was = mode != OFF;
        if (was) NightLog.end(appCtx, false);
        reset();
        persist();
        MainActivity.js("ytl.stopAtEnd(false);ytl.countMode&&ytl.countMode(false);ytl.restoreVolume()");
        if (was && c != null) toast(c, "已关闭睡眠定时");
        pushState();
    }

    /** Fade sync for the page (299): "ends in leftMs, lasts fadeMs"; the page steps the volume itself. */
    private static void sendFade(long left) {
        MainActivity.js("ytl.fadeStart?ytl.fadeStart(" + Math.max(0, left) + "," + fadeMs + "):ytl.setVolume("
                + String.format(java.util.Locale.US, "%.3f", Logic.fadeVolume(left, fadeMs)) + ")");
    }

    /** Notification "+15 min" (14): only for a running minutes timer; undoes a fade in progress. */
    public static void extend(Context c) {
        if (mode <= 0) return;
        long now = SystemClock.elapsedRealtime();
        int step = extendMin(c);
        if (held) heldLeft += step * 60_000L;
        else endAt = Math.max(endAt, now) + step * 60_000L;
        if (fading) {
            fading = false;
            MainActivity.js("ytl.restoreVolume()");
        }
        H.removeCallbacks(TICK);
        H.post(TICK);
        toast(c, "睡眠定时：+" + step + " 分钟（剩余 " + minutesLeft() + " 分钟）");
        persist();
        pushState();
    }

    /**
     * Notification / media-control button (300): off -> the default timer; running -> off.
     * Pressed again within 5 s after switching off, the timer that was running comes back as it was.
     * Changing the length is done in the app (moon button), not by cycling through options.
     */
    public static void cycle(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (mode == OFF) {
            if (undoMode != OFF && now < undoUntil) {
                int m = undoMode;
                long left = undoLeftMs;
                int cnt = undoCount;
                undoMode = OFF;
                startInternal(c, m, left, cnt, true);
                toast(c, "已恢复睡眠定时：" + (m > 0 ? "剩余 " + minutesLeft() + " 分钟" : optionLabel(m)));
                return;
            }
            undoMode = OFF;
            start(c, Prefs.sleepDefault(c));
            return;
        }
        undoMode = mode;
        undoLeftMs = mode > 0 ? Math.max(60_000L, endAt - now) : 0L;
        undoCount = countLeft;
        undoUntil = now + UNDO_MS;
        reset();
        persist();
        MainActivity.js("ytl.stopAtEnd(false);ytl.countMode&&ytl.countMode(false);ytl.restoreVolume()");
        toast(c, "已关闭睡眠定时 · 5 秒内再按一次可撤销");
        pushState();
        H.postDelayed(SleepTimer::pushState, UNDO_MS + 100);
    }

    /** Label of the notification sleep button. */
    public static String buttonLabel() {
        String s = status();
        if (s != null) return s;
        if (undoMode != OFF && SystemClock.elapsedRealtime() < undoUntil) return "撤销关闭定时";
        return "睡眠定时";
    }

    /** Called from JS when "end of current video" mode paused playback. */
    public static void onEndOfVideoFired() {
        boolean was = mode != OFF;
        if (was) NightLog.end(appCtx, true);
        reset();
        persist();
        MainActivity.js("ytl.countMode&&ytl.countMode(false)");
        pushState();
        if (was) PlaybackService.onSleepEnded();
    }

    /** "After N videos": the page reports each video that played to its end (16). */
    public static void onVideoEnded() {
        if (!isCount(mode) || countLeft <= 1) return;
        countLeft--;
        if (countLeft <= 1) MainActivity.js("ytl.countMode(false);ytl.stopAtEndNext()");
        persist();
        pushState();
    }

    private static void reset() {
        H.removeCallbacks(TICK);
        held = false;
        heldLeft = 0L;
        mode = OFF;
        endAt = 0L;
        fading = false;
        countLeft = 0;
    }

    /** Notification, page badge and "sleeping" flag follow the timer. */
    private static void pushState() {
        PlaybackService.refresh();   // re-renders only what changed (263)
        MainActivity.onSleepState(mode != OFF);
        String js = "ytl.sleepState&&ytl.sleepState(" + (mode != OFF) + ");ytl.badge&&ytl.badge("
                + org.json.JSONObject.quote(badge()) + ")";
        if (js.equals(lastPushed) && fading) return;   // the badge text did not change during the fade
        lastPushed = js;
        MainActivity.js(js);
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (mode <= 0 || held) return;
            long left = endAt - SystemClock.elapsedRealtime();
            if (left <= 0) {
                MainActivity.js("ytl.sleepPause()");
                NightLog.end(appCtx, true);
                reset();
                persist();
                pushState();
                PlaybackService.onSleepEnded();
                return;
            }
            if (left <= fadeMs) {
                // the page interpolates every 250 ms on (left / fade)² (298/299); every 5 s it gets the
                // remaining time again, which also picks up a page reloaded during the fade
                fading = true;
                sendFade(left);
                H.postDelayed(this, Math.min(5000L, Math.max(250L, left)));
            } else {
                // refresh the notification once a minute, wake up exactly when the fade starts; in the
                // last minute every 5 s so the badge can count seconds (308)
                long untilFade = left - fadeMs;
                long delay;
                if (left <= 60_000L) {
                    delay = Math.min(5000L, untilFade);
                } else {
                    long untilMinute = left % 60_000L;
                    if (untilMinute < 1000) untilMinute = 60_000L;
                    delay = Math.min(untilFade, untilMinute);
                }
                H.postDelayed(this, Math.max(250L, delay));
            }
            pushState();
        }
    };

    private static void toast(Context c, String s) {
        if (c == null) return;
        Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }
}
