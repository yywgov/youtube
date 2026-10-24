package com.google.android.youtube.pro;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The app's background threads in one place (262): one serial executor for small disk writes
 * (diagnostics log, rules cache) and one small pool for network work (SponsorBlock, cover art,
 * remote rules, update checks and downloads). Idle threads go away after 30 s, so nothing sits
 * around while the phone sleeps.
 */
public final class Bg {
    private Bg() {}

    /** Disk: strictly one task after another (log lines keep their order). */
    public static final ExecutorService DISK = pool("ytl-disk", 1);
    /** Network: two at a time, so a long APK download never blocks the SponsorBlock lookup. */
    public static final ExecutorService NET = pool("ytl-net", 2);

    private static ExecutorService pool(final String name, int threads) {
        final AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ThreadPoolExecutor ex = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), tf);
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }
}
