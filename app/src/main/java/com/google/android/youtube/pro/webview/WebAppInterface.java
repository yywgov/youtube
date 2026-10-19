package com.google.android.youtube.pro.webview;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

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
        return Prefs.jsConfig(activity);
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

    @JavascriptInterface
    public void reloadPage() {
        main.post(activity::reloadFeed);
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
                String cats = URLEncoder.encode("[\"sponsor\",\"selfpromo\",\"interaction\"]", "UTF-8");
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
                        if ("skip".equals(o.optString("actionType", "skip"))) {
                            out.put(new JSONArray().put(seg.getDouble(0)).put(seg.getDouble(1)));
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
