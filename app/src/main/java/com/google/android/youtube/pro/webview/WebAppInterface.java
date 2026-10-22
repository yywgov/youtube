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
            o.put("stopAtEnd", SleepTimer.stopsAtEndNow());
            o.put("countMode", SleepTimer.countRunning());
            o.put("sleeping", SleepTimer.active());
            o.put("badge", SleepTimer.badge());
            o.put("sleepMode", MainActivity.sleepModeOn() && SleepTimer.active());
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

    /** "After N videos" sleep mode: a video played to its end (16). */
    @JavascriptInterface
    public void videoEnded(final String videoId) {
        main.post(SleepTimer::onVideoEnded);
    }

    /** Metered network (mobile data): default quality "Wi-Fi best, mobile 720p" (29). */
    @JavascriptInterface
    public boolean isMetered() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    activity.getApplicationContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.isActiveNetworkMetered();
        } catch (Exception e) {
            return false;
        }
    }

    /** Speed the user picked, remembered when that setting is on (30). */
    @JavascriptInterface
    public void saveRate(final String rate) {
        try {
            double r = Double.parseDouble(rate);
            if (r >= 0.25 && r <= 2) Prefs.put(activity, Prefs.RATE, String.valueOf(r));
        } catch (Exception ignored) {}
    }

    /** Last video + position, offered again when the app is reopened after being killed (27). */
    @JavascriptInterface
    public void savePos(final String videoId, final long sec, final String title) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{6,20}")) return;
        Prefs.get(activity).edit()
                .putString(Prefs.LAST_VIDEO, videoId)
                .putLong(Prefs.LAST_POS, Math.max(0, sec))
                .putString(Prefs.LAST_TITLE, title == null ? "" : (title.length() > 120 ? title.substring(0, 120) : title))
                .putLong(Prefs.LAST_AT, System.currentTimeMillis())
                .apply();
    }

    /** Native playback tools sheet (sleep, black screen, touch lock, speed, loop...). */
    @JavascriptInterface
    public void openTools() {
        main.post(activity::openTools);
    }

    /** Long-press on a video card (123): native quick menu. */
    @JavascriptInterface
    public void cardMenu(final String json) {
        if (json == null || json.length() > 2000) return;
        main.post(() -> activity.cardMenu(json));
    }

    /** Search box opened (124). */
    @JavascriptInterface
    public void showKeyboard() {
        main.post(activity::showKeyboard);
    }

    /** Page background colour for the system bars (92/145). */
    @JavascriptInterface
    public void pageTheme(final String color) {
        main.post(() -> activity.applyTheme(color));
    }

    /** Remote rules (73): the cached, already verified rules.json, or "" (built-in rules only). */
    @JavascriptInterface
    public String getRemoteRules() {
        return com.google.android.youtube.pro.RemoteRules.cached(activity);
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
                c.setRequestProperty("User-Agent", "YiWei");
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
