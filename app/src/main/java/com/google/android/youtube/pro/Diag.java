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
    /** The last 3 crashes, newest last (251); shown first in a problem report. */
    public static final String CRASH = "crash.log";
    /** Lines older than this many days are dropped once per start (414). */
    static final int KEEP_DAYS = 30;
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
        final Context a = app;
        IO.execute(() -> { prune(a, DIAG); prune(a, ADS); });   // (414)
    }

    /** One separator line per start (251), so a report shows where each run begins. */
    public static void session(String version) {
        append(DIAG, now() + " [start] ====== v" + clean(version, 40) + " · " + Build.MANUFACTURER + " " + Build.MODEL
                + " · SDK " + Build.VERSION.SDK_INT + " ======");
    }

    /** Lines whose "yyyy-MM-dd" stamp is before {@code cutoff} go; lines without a stamp stay (414). */
    static String dropBefore(String text, String cutoff) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder b = new StringBuilder(text.length());
        int i = 0, n = text.length();
        while (i < n) {
            int e = text.indexOf('\n', i);
            if (e < 0) e = n - 1;
            String line = text.substring(i, e + 1);
            boolean stamped = line.length() >= 10 && line.charAt(4) == '-' && line.charAt(7) == '-'
                    && Character.isDigit(line.charAt(0)) && Character.isDigit(line.charAt(9));
            if (!stamped || line.substring(0, 10).compareTo(cutoff) >= 0) b.append(line);
            i = e + 1;
        }
        return b.toString();
    }

    private static void prune(Context c, String name) {
        try {
            File f = new File(c.getFilesDir(), name);
            if (!f.exists() || f.length() == 0) return;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.add(java.util.Calendar.DAY_OF_MONTH, -KEEP_DAYS);
            String cutoff = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
            String old = new String(readBytes(f), UTF8);
            String kept = dropBefore(old, cutoff);
            if (kept.length() == old.length()) return;
            synchronized (Diag.class) {
                try (FileOutputStream out = new FileOutputStream(f, false)) { out.write(kept.getBytes(UTF8)); }
            }
        } catch (Throwable ignored) {}
    }

    /** Newest crash block, "" when there is none (251). */
    public static String lastCrash(Context c) {
        java.util.List<String> b = crashBlocks(read(c, CRASH));
        return b.isEmpty() ? "" : "=== " + b.get(b.size() - 1);
    }

    private static java.util.List<String> crashBlocks(String text) {
        java.util.List<String> blocks = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) return blocks;
        for (String x : ("\n" + text).split("\n=== ")) if (!x.trim().isEmpty()) blocks.add(x.trim());
        return blocks;
    }

    /** The crash file with {@code block} added, only the newest {@code keep} kept (251). */
    static String addCrash(String old, String block, int keep) {
        java.util.List<String> blocks = crashBlocks(old);
        blocks.add(block.trim());
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, blocks.size() - keep); i < blocks.size(); i++) sb.append("=== ").append(blocks.get(i)).append('\n');
        return sb.toString();
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
                    // the whole stack, readable, in its own small file (251)
                    try {
                        File cf = new File(c.getFilesDir(), CRASH);
                        String old = cf.exists() && cf.length() < 64 * 1024 ? new String(readBytes(cf), UTF8) : "";
                        String text = addCrash(old, now() + " thread=" + thread.getName() + "\n" + st, 3);
                        try (FileOutputStream out = new FileOutputStream(cf, false)) { out.write(text.getBytes(UTF8)); }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(thread, e);
        });
    }
}
