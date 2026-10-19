package com.google.android.youtube.pro.webview;

import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full page loads of a watch page (opening a link, reload) carry the player
 * response inline in the HTML. The page is fetched natively and the ad keys
 * are renamed before the WebView ever parses it, so YouTube's player never
 * sees an ad schedule. Anything unexpected (redirect, error, non-HTML) returns
 * null and the WebView loads the page itself as usual.
 */
public final class WatchPageFilter {

    private static final String[] AD_KEYS = {"adPlacements", "playerAds", "adSlots", "adBreakHeartbeatParams"};

    /** User agent the WebView uses (set by MainActivity), used if the request lacks one. */
    public static volatile String userAgent = null;

    private WatchPageFilter() {}

    static boolean applies(WebResourceRequest req) {
        if (req == null || !req.isForMainFrame() || !"GET".equalsIgnoreCase(req.getMethod())) return false;
        Uri u = req.getUrl();
        if (u == null || !"https".equals(u.getScheme())) return false;
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!(host.equals("m.youtube.com") || host.equals("www.youtube.com") || host.equals("youtube.com"))) return false;
        String path = u.getPath() == null ? "" : u.getPath();
        return path.equals("/watch") || path.startsWith("/live/");
    }

    /** Rename ad keys in plain JSON, in \"escaped\" JSON and in \x22hex-escaped\x22 JS strings. */
    static String strip(String s) {
        for (String k : AD_KEYS) {
            if (s.indexOf(k) < 0) continue;
            s = s.replace("\"" + k + "\"", "\"" + k + "_ytl\"")
                    .replace("\\\"" + k + "\\\"", "\\\"" + k + "_ytl\\\"")
                    .replace("\\x22" + k + "\\x22", "\\x22" + k + "_ytl\\x22");
        }
        return s;
    }

    static WebResourceResponse fetch(WebResourceRequest req) {
        String url = req.getUrl().toString();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setUseCaches(false);
            boolean hasUa = false, hasLang = false;
            Map<String, String> rh = req.getRequestHeaders();
            if (rh != null) {
                for (Map.Entry<String, String> e : rh.entrySet()) {
                    String k = e.getKey();
                    if (k == null || e.getValue() == null) continue;
                    String lk = k.toLowerCase(Locale.ROOT);
                    // let HttpURLConnection negotiate gzip itself (it then decodes transparently)
                    if (lk.equals("accept-encoding") || lk.equals("cookie") || lk.equals("host")) continue;
                    if (lk.equals("user-agent")) hasUa = true;
                    if (lk.equals("accept-language")) hasLang = true;
                    c.setRequestProperty(k, e.getValue());
                }
            }
            if (!hasUa && userAgent != null) c.setRequestProperty("User-Agent", userAgent);
            if (!hasLang) c.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag());
            CookieManager cm = CookieManager.getInstance();
            String cookie = cm.getCookie(url);
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);

            int code = c.getResponseCode();
            Map<String, List<String>> hf = c.getHeaderFields();
            if (hf != null) {
                for (Map.Entry<String, List<String>> e : hf.entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie") && e.getValue() != null) {
                        for (String v : e.getValue()) cm.setCookie(url, v);
                    }
                }
            }
            if (code != 200) return null;
            String ct = c.getContentType();
            if (ct == null || !ct.toLowerCase(Locale.ROOT).contains("text/html")) return null;
            Charset cs = Charset.forName("UTF-8");
            int i = ct.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (i >= 0) {
                try { cs = Charset.forName(ct.substring(i + 8).replace("\"", "").split(";")[0].trim()); } catch (Exception ignored) {}
            }
            byte[] body;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                body = bos.toByteArray();
            }
            String html = strip(new String(body, cs));

            Map<String, String> headers = new HashMap<>();
            if (hf != null) {
                for (Map.Entry<String, List<String>> e : hf.entrySet()) {
                    String k = e.getKey();
                    if (k == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                    String lk = k.toLowerCase(Locale.ROOT);
                    if (lk.equals("content-encoding") || lk.equals("content-length") || lk.equals("transfer-encoding")
                            || lk.equals("set-cookie") || lk.equals("content-type") || lk.startsWith("x-android")) continue;
                    headers.put(k, join(e.getValue()));
                }
            }
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers,
                    new ByteArrayInputStream(html.getBytes(Charset.forName("UTF-8"))));
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static String join(List<String> v) {
        if (v.size() == 1) return v.get(0);
        StringBuilder b = new StringBuilder();
        for (String s : v) {
            if (b.length() > 0) b.append(", ");
            b.append(s);
        }
        return b.toString();
    }
}
