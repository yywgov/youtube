package com.google.android.youtube.pro.webview;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import com.google.android.youtube.pro.Diag;
import com.google.android.youtube.pro.MainActivity;
import com.google.android.youtube.pro.PlaybackService;
import com.google.android.youtube.pro.Prefs;
import com.google.android.youtube.pro.SleepTimer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JS bridge, exposed as window.YTL. Methods are called on the JavaBridge
 * thread; everything that touches UI or services is posted to the main thread,
 * network work goes to a background executor.
 */
public class WebAppInterface {
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private final MainActivity activity;
    private final Handler main = new Handler(Looper.getMainLooper());

    public WebAppInterface(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public String getConfig() {
        // settings + the state a freshly (re)loaded page must pick up again:
        // "sleep at end of video" and whether the app is in the background / screen off
        try {
            JSONObject o = new JSONObject(Prefs.jsConfig(activity));
            o.put("stopAtEnd", SleepTimer.mode() == SleepTimer.END_OF_VIDEO);
            o.put("bg", !MainActivity.isVisible());
            return o.toString();
        } catch (Exception e) {
            return Prefs.jsConfig(activity);
        }
    }

    @JavascriptInterface
    public void mediaMeta(final String title, final String artist, final String art, final long durMs, final String videoId) {
        main.post(() -> PlaybackService.onMeta(activity, title, artist, art, durMs));
    }

    @JavascriptInterface
    public void mediaState(final String state, final long posMs, final float rate) {
        main.post(() -> {
            PlaybackService.onState(activity, state, posMs, rate);
            activity.onPlayingChanged("playing".equals(state) || "buffering".equals(state));
        });
    }

    @JavascriptInterface
    public void setAtTop(final boolean top) {
        main.post(() -> activity.setPageAtTop(top));
    }

    @JavascriptInterface
    public void setPortrait(final boolean portrait) {
        main.post(() -> activity.portraitVideo = portrait);
    }

    @JavascriptInterface
    public void openSettings() {
        main.post(activity::openSettings);
    }

    /** Fresh reload after the script dropped YouTube's client-side caches. */
    @JavascriptInterface
    public void hardReload() {
        main.post(activity::hardReload);
    }

    @JavascriptInterface
    public void reloadPage() {
        main.post(activity::hardReload);
    }

    @JavascriptInterface
    public void sleepFired() {
        main.post(SleepTimer::onEndOfVideoFired);
    }

    @JavascriptInterface
    public void toast(final String text) {
        main.post(() -> Toast.makeText(activity.getApplicationContext(), text, Toast.LENGTH_SHORT).show());
    }

    /** SponsorBlock segments, fetched natively (no CSP issues, off the UI thread). */
    @JavascriptInterface
    public void fetchSegments(final String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{6,20}")) return;
        NET.execute(() -> {
            JSONArray out = new JSONArray();
            HttpURLConnection c = null;
            try {
                java.util.List<String> chosen = Prefs.sponsorCats(activity);
                if (chosen.isEmpty()) throw new Exception("no categories");
                JSONArray catArr = new JSONArray();
                for (String k : chosen) catArr.put(k);
                String cats = URLEncoder.encode(catArr.toString(), "UTF-8");
                URL u = new URL("https://sponsor.ajay.app/api/skipSegments?videoID=" + videoId + "&categories=" + cats);
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("User-Agent", "YTLite");
                if (c.getResponseCode() == 200) {
                    String body = read(c.getInputStream());
                    JSONArray arr = new JSONArray(body);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        JSONArray seg = o.getJSONArray("segment");
                        String cat = o.optString("category", "sponsor");
                        if ("skip".equals(o.optString("actionType", "skip")) && chosen.contains(cat)) {
                            out.put(new JSONArray().put(seg.getDouble(0)).put(seg.getDouble(1)).put(cat));
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
            final String js = "ytl.onSegments(" + JSONObject.quote(videoId) + "," + out + ")";
            main.post(() -> MainActivity.js(js));
        });
    }

    /** Diagnostic line from the page script (self check, retries, module errors). */
    @JavascriptInterface
    public void log(final String tag, final String msg) {
        if (Diag.allowFromPage()) Diag.log(tag, msg);
    }

    /** An ad that still reached the player despite the response filtering. */
    @JavascriptInterface
    public void logAd(final String videoId, final String type, final String page) {
        if (Diag.allowFromPage()) Diag.ad(videoId, type, page);
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }
}
