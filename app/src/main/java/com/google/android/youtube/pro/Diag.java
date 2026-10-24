package com.google.android.youtube.pro;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small local logs, kept on the device only and copied out from the settings screen:
 *  - diag.log : startup self check, retries, JS module errors, crashes, renderer restarts
 *  - ads.log  : ads that still reached the player (video id, time, how it was detected)
 * Each file is capped (oldest lines dropped), writes happen off the UI thread.
 */
public final class Diag {
    public static final String DIAG = "diag.log";
    public static final String ADS = "ads.log";
    private static final int MAX_BYTES = 48 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    // shared serial disk thread (262); the class also builds alone for the JVM tests
    private static final ExecutorService IO = diskExecutor();
    private static volatile Context app;
    private static volatile boolean crashHandlerInstalled = false;
    // simple flood guard for messages coming from the page
    private static long windowStart = 0;
    private static int windowCount = 0;

    private Diag() {}

    public static void init(Context c) {
        app = c.getApplicationContext();
        installCrashHandler();
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    /** Diagnostic line (async). */
    public static void log(String tag, String msg) {
        append(DIAG, now() + " [" + clean(tag, 24) + "] " + clean(msg, 600));
    }

    /** Line coming from the page script: rate limited to 40 per minute. */
    public static synchronized boolean allowFromPage() {
        long t = System.currentTimeMillis();
        if (t - windowStart > 60000) { windowStart = t; windowCount = 0; }
        return ++windowCount <= 40;
    }

    public static void ad(String videoId, String type, String page) { ad(videoId, type, page, null); }

    /** Missed ad with what is needed to tell which layer failed (280): self check, login, rules, native rewrite, WebView. */
    public static void ad(String videoId, String type, String page, String context) {
        append(ADS, now() + "\t" + clean(videoId, 20) + "\t" + clean(type, 32) + "\t" + clean(page, 16)
                + "\thttps://youtu.be/" + clean(videoId, 20) + (context == null || context.isEmpty() ? "" : "\t" + clean(context, 300)));
    }

    private static ExecutorService diskExecutor() {
        try {
            return Bg.DISK;
        } catch (Throwable t) {
            return Executors.newSingleThreadExecutor();
        }
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static void append(final String name, final String line) {
        final Context c = app;
        if (c == null) return;
        IO.execute(() -> write(c, name, line));
    }

    private static synchronized void write(Context c, String name, String line) {
        try {
            File f = new File(c.getFilesDir(), name);
            byte[] add = (line + "\n").getBytes(UTF8);
            if (f.exists() && f.length() + add.length > MAX_BYTES) {
                byte[] old = readBytes(f);
                int keepFrom = Math.max(0, old.length - MAX_BYTES / 2);
                while (keepFrom < old.length && keepFrom > 0 && old[keepFrom - 1] != '\n') keepFrom++;
                try (FileOutputStream out = new FileOutputStream(f, false)) {
                    out.write(old, keepFrom, old.length - keepFrom);
                    out.write(add);
                }
            } else {
                try (FileOutputStream out = new FileOutputStream(f, true)) {
                    out.write(add);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static byte[] readBytes(File f) throws java.io.IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            if (off < b.length) {
                byte[] t = new byte[off];
                System.arraycopy(b, 0, t, 0, off);
                return t;
            }
            return b;
        }
    }

    /** Whole log as text ("" if empty). Call off the UI thread if you can; files are small. */
    public static String read(Context c, String name) {
        try {
            File f = new File(c.getFilesDir(), name);
            if (!f.exists()) return "";
            return new String(readBytes(f), UTF8);
        } catch (Throwable t) {
            return "";
        }
    }

    public static int lines(Context c, String name) {
        String s = read(c, name);
        if (s.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    public static void clear(Context c, String name) {
        try { new File(c.getFilesDir(), name).delete(); } catch (Throwable ignored) {}
    }

    /** Header for an exported log: app + device, so a report is self-contained. */
    public static String header(String version) {
        return "YiWei " + version + " · Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ") · "
                + Build.MANUFACTURER + " " + Build.MODEL + "\n";
    }

    private static void installCrashHandler() {
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                Context c = app;
                if (c != null) {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String st = sw.toString();
                    if (st.length() > 3000) st = st.substring(0, 3000);
                    // synchronous: the process is about to die
                    write(c, DIAG, now() + " [crash] thread=" + thread.getName() + " " + st.replace("\n", " | "));
                }
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(thread, e);
        });
    }
}
