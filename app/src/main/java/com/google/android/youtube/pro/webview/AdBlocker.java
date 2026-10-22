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

    private AdBlocker() {}

    public static boolean shouldBlock(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        for (String s : BLOCKED_HOST_SUFFIXES) {
            if (host.equals(s) || host.endsWith("." + s)) return true;
        }
        String path = uri.getPath();
        if (path == null) return false;
        if (host.endsWith("youtube.com")) {
            for (String p : BLOCKED_YT_PATHS) if (path.startsWith(p)) return true;
        }
        if (host.equals("www.google.com") && (path.startsWith("/pagead/") || path.startsWith("/ads/"))) return true;
        if (host.equals("play.google.com") && path.startsWith("/log")) return true;
        return false;
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
