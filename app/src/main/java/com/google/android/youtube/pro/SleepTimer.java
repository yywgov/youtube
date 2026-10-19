package com.google.android.youtube.pro;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

/**
 * Sleep timer. Runs on the main looper; while playing the service holds a
 * partial wake lock so the ticks keep coming with the screen off.
 * The last 60 s fade the volume out, then playback is paused.
 */
public final class SleepTimer {
    public static final int OFF = 0;
    public static final int END_OF_VIDEO = -1;
    public static final int[] OPTIONS = {15, 30, 45, 60, 90, END_OF_VIDEO};
    private static final long FADE_MS = 60_000L;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static int mode = OFF;
    private static long endAt = 0L; // elapsedRealtime
    private static boolean fading = false;

    private SleepTimer() {}

    public static int mode() { return mode; }
    public static boolean active() { return mode != OFF; }

    public static String optionLabel(int m) {
        if (m == END_OF_VIDEO) return "当前视频结束时";
        return m + " 分钟";
    }

    /** Short status text for the notification, or null when off. */
    public static String status() {
        if (mode == OFF) return null;
        if (mode == END_OF_VIDEO) return "睡眠定时：本视频结束";
        long left = Math.max(0, endAt - SystemClock.elapsedRealtime());
        long min = (left + 59_999L) / 60_000L;
        return "睡眠定时：剩余 " + min + " 分钟";
    }

    public static void start(Context c, int minutes) {
        reset();
        mode = minutes;
        if (minutes == END_OF_VIDEO) {
            MainActivity.js("ytl.stopAtEnd(true)");
        } else {
            endAt = SystemClock.elapsedRealtime() + minutes * 60_000L;
            H.post(TICK);
        }
        toast(c, "睡眠定时：" + optionLabel(minutes));
        PlaybackService.refresh();
    }

    public static void cancel(Context c) {
        boolean was = mode != OFF;
        reset();
        MainActivity.js("ytl.stopAtEnd(false);ytl.restoreVolume()");
        if (was && c != null) toast(c, "已关闭睡眠定时");
        PlaybackService.refresh();
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
        reset();
        PlaybackService.refresh();
    }

    private static void reset() {
        H.removeCallbacks(TICK);
        mode = OFF;
        endAt = 0L;
        fading = false;
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (mode <= 0) return;
            long left = endAt - SystemClock.elapsedRealtime();
            if (left <= 0) {
                MainActivity.js("ytl.sleepPause()");
                reset();
                PlaybackService.refresh();
                return;
            }
            if (left <= FADE_MS) {
                fading = true;
                double vol = (double) left / FADE_MS;
                MainActivity.js("ytl.setVolume(" + String.format(java.util.Locale.US, "%.3f", vol) + ")");
                H.postDelayed(this, 1000);
            } else {
                // refresh the notification once a minute, wake up exactly when the fade starts
                long untilFade = left - FADE_MS;
                long untilMinute = left % 60_000L;
                if (untilMinute < 1000) untilMinute = 60_000L;
                H.postDelayed(this, Math.max(1000, Math.min(untilFade, untilMinute)));
            }
            PlaybackService.refresh();
        }
    };

    private static void toast(Context c, String s) {
        if (c == null) return;
        Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }
}
