package com.google.android.youtube.pro.webview;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Network-level blocking of ad / tracking / telemetry requests.
 * Runs on WebView's IO thread, so it only does cheap string checks.
 * Deliberately NOT blocked: /api/stats/watchtime and /api/stats/playback
 * (watch history and resume position depend on them) and googlevideo.com.
 */
public final class AdBlocker {

    private static final String[] BLOCKED_HOST_SUFFIXES = {
            "doubleclick.net",
            "googleadservices.com",
            "googlesyndication.com",
            "google-analytics.com",
            "googletagmanager.com",
            "googletagservices.com",
            "adservice.google.com",
            "app-measurement.com",
            "imasdk.googleapis.com",
            "2mdn.net",
    };

    private static final String[] BLOCKED_YT_PATHS = {
            "/pagead/",
            "/ptracking",
            "/api/stats/ads",
            "/api/stats/atr",
            "/youtubei/v1/log_event",
            "/youtubei/v1/player/ad_break",
            "/get_midroll_info",
            "/pcs/activeview",
            "/csi_204",
            "/error_204",          // client error beacons (69)
    };

    /** Extra YouTube paths from the verified rules.json (278), already checked by Logic.validBlockPath. */
    private static volatile String[] extraPaths = new String[0];

    // hits per rule today (281): counted in memory, written to the preferences at most once an hour
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> HITS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile String hitsDay = "";
    private static volatile long lastFlush = 0;

    private AdBlocker() {}

    public static boolean shouldBlock(Uri uri) {
        String rule = match(uri);
        if (rule == null) return false;
        count(rule);
        return true;
    }

    /** The rule a request falls under, or null. Cheap string checks only (WebView IO thread). */
    static String match(Uri uri) {
        String host = uri.getHost();
        if (host == null) return null;
        host = host.toLowerCase();
        for (String s : BLOCKED_HOST_SUFFIXES) {
            if (host.equals(s) || host.endsWith("." + s)) return s;
        }
        String path = uri.getPath();
        if (path == null) return null;
        if (host.endsWith("youtube.com")) {
            for (String p : BLOCKED_YT_PATHS) if (path.startsWith(p)) return p;
            for (String p : extraPaths) if (path.startsWith(p)) return "remote:" + p;
        }
        if (host.equals("www.google.com") && (path.startsWith("/pagead/") || path.startsWith("/ads/"))) return "google.com/ads";
        if (host.equals("play.google.com") && path.startsWith("/log")) return "play.google.com/log";
        return null;
    }

    /** Remote paths (278); anything that fails the check is dropped and logged. */
    public static void setExtraPaths(java.util.List<String> paths) {
        java.util.List<String> ok = new java.util.ArrayList<>();
        if (paths != null) {
            for (String p : paths) {
                if (com.google.android.youtube.pro.Logic.validBlockPath(p) && !ok.contains(p) && ok.size() < 30) ok.add(p);
                else com.google.android.youtube.pro.Diag.log("rules", "block path refused: " + p);
            }
        }
        extraPaths = ok.toArray(new String[0]);
    }

    public static int extraCount() { return extraPaths.length; }

    private static String today() {
        return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
    }

    private static void count(String rule) {
        String d = today();
        if (!d.equals(hitsDay)) { HITS.clear(); hitsDay = d; }
        java.util.concurrent.atomic.AtomicInteger n = HITS.get(rule);
        if (n == null) {
            java.util.concurrent.atomic.AtomicInteger fresh = new java.util.concurrent.atomic.AtomicInteger();
            n = HITS.putIfAbsent(rule, fresh);
            if (n == null) n = fresh;
        }
        n.incrementAndGet();
    }

    /** Today's counts from the last run of the day (281): a restart does not start again at zero. */
    public static void load(android.content.Context c) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(com.google.android.youtube.pro.Prefs.str(c, com.google.android.youtube.pro.Prefs.BLOCK_STATS, "{}"));
            if (!today().equals(o.optString("day"))) return;
            hitsDay = o.optString("day");
            org.json.JSONObject h = o.optJSONObject("c");
            if (h == null) return;
            java.util.Iterator<String> it = h.keys();
            while (it.hasNext()) {
                String k = it.next();
                HITS.putIfAbsent(k, new java.util.concurrent.atomic.AtomicInteger(0));
                HITS.get(k).addAndGet(h.optInt(k, 0));
            }
        } catch (Throwable ignored) {}
    }

    /** Write today's counts (281); at most once an hour unless forced. */
    public static void flush(android.content.Context c, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastFlush < 3600_000L) return;
        lastFlush = now;
        try {
            org.json.JSONObject h = new org.json.JSONObject();
            for (java.util.Map.Entry<String, java.util.concurrent.atomic.AtomicInteger> e : HITS.entrySet()) h.put(e.getKey(), e.getValue().get());
            org.json.JSONObject o = new org.json.JSONObject().put("day", hitsDay.isEmpty() ? today() : hitsDay).put("c", h);
            com.google.android.youtube.pro.Prefs.put(c, com.google.android.youtube.pro.Prefs.BLOCK_STATS, o.toString());
        } catch (Throwable ignored) {}
    }

    /** "今天拦截 1234 次 · 最多：/youtubei/v1/log_event 800、…" for the logs page. */
    public static String summary() {
        int total = 0;
        java.util.List<java.util.Map.Entry<String, Integer>> l = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, java.util.concurrent.atomic.AtomicInteger> e : HITS.entrySet()) {
            total += e.getValue().get();
            l.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().get()));
        }
        if (total == 0) return "今天还没有拦截记录";
        java.util.Collections.sort(l, (x, y) -> y.getValue() - x.getValue());
        StringBuilder b = new StringBuilder("今天拦截 " + total + " 次 · 最多：");
        for (int i = 0; i < Math.min(3, l.size()); i++) b.append(i == 0 ? "" : "、").append(l.get(i).getKey()).append(' ').append(l.get(i).getValue());
        return b.toString();
    }

    /** For the problem report (281): every rule with today's count, unused ones (0) included. */
    public static String report() {
        StringBuilder b = new StringBuilder();
        java.util.List<String> all = new java.util.ArrayList<>();
        java.util.Collections.addAll(all, BLOCKED_HOST_SUFFIXES);
        java.util.Collections.addAll(all, BLOCKED_YT_PATHS);
        for (String p : extraPaths) all.add("remote:" + p);
        all.add("google.com/ads");
        all.add("play.google.com/log");
        for (String r : all) {
            java.util.concurrent.atomic.AtomicInteger n = HITS.get(r);
            b.append(r).append('=').append(n == null ? 0 : n.get()).append(' ');
        }
        return b.toString().trim();
    }

    /** Empty 204 that satisfies CORS (incl. credentialed fetches) so callers don't retry. */
    public static WebResourceResponse empty(WebResourceRequest request) {
        Map<String, String> h = new HashMap<>();
        String origin = null;
        if (request != null && request.getRequestHeaders() != null) {
            origin = request.getRequestHeaders().get("Origin");
            if (origin == null) origin = request.getRequestHeaders().get("origin");
        }
        h.put("Access-Control-Allow-Origin", origin != null ? origin : "*");
        if (origin != null) h.put("Access-Control-Allow-Credentials", "true");
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/plain", "utf-8", 204, "No Content", h,
                new ByteArrayInputStream(new byte[0]));
    }
}
