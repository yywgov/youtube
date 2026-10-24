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

/**
 * JS bridge, exposed as window.YTL. Methods are called on the JavaBridge
 * thread; everything that touches UI or services is posted to the main thread,
 * network work goes to a background executor.
 */
public class WebAppInterface {
    /**
     * Weak (245): after recreate() the old WebView's bridge must not keep the old activity alive or
     * act on it; every UI call checks it is still there, everything else uses the application context.
     */
    private final java.lang.ref.WeakReference<MainActivity> ref;
    private final android.content.Context app;
    private final Handler main = new Handler(Looper.getMainLooper());

    public WebAppInterface(MainActivity activity) {
        this.ref = new java.lang.ref.WeakReference<>(activity);
        this.app = activity.getApplicationContext();
    }

    /** The activity, or null once it is finishing / destroyed. */
    private MainActivity act() {
        MainActivity a = ref.get();
        return a == null || a.isFinishing() || a.isDestroyed() ? null : a;
    }

    private interface UiJob { void run(MainActivity a); }

    /** Run on the main thread against the activity, dropped when it is gone by then. */
    private void onUi(final UiJob job) {
        main.post(() -> {
            MainActivity a = act();
            if (a != null) job.run(a);
        });
    }

    @JavascriptInterface
    public String getConfig() {
        // settings + the state a freshly (re)loaded page must pick up again:
        // "sleep at end of video" and whether the app is in the background / screen off
        try {
            JSONObject o = new JSONObject(Prefs.jsConfig(app));
            o.put("stopAtEnd", SleepTimer.stopsAtEndNow());
            o.put("countMode", SleepTimer.countRunning());
            o.put("sleeping", SleepTimer.active());
            o.put("badge", SleepTimer.badge());
            o.put("sleepMode", MainActivity.sleepModeOn() && SleepTimer.active());
            o.put("bg", !MainActivity.isVisible());
            MainActivity.onPageReady();   // queued commands for this page run now (247)
            o.put("adKeys", new JSONArray(WatchPageFilter.adKeysJson()));   // one list with the native filter (279)
            o.put("strings", pageStrings(app));   // (381)
            return o.toString();
        } catch (Exception e) {
            return Prefs.jsConfig(app);
        }
    }

    /** Texts the page shows, from the string resources (381); the script falls back to its own copy. */
    static JSONObject pageStrings(android.content.Context c) {
        JSONObject o = new JSONObject();
        int[] ids = {com.google.android.youtube.pro.R.string.js_offline, com.google.android.youtube.pro.R.string.js_new_videos,
                com.google.android.youtube.pro.R.string.js_no_new, com.google.android.youtube.pro.R.string.js_filtered_n,
                com.google.android.youtube.pro.R.string.js_wl_login, com.google.android.youtube.pro.R.string.js_wl_ok,
                com.google.android.youtube.pro.R.string.js_wl_fail, com.google.android.youtube.pro.R.string.js_hidden,
                com.google.android.youtube.pro.R.string.js_undo, com.google.android.youtube.pro.R.string.js_skipped,
                com.google.android.youtube.pro.R.string.js_show_all, com.google.android.youtube.pro.R.string.js_filtered_all};
        String[] keys = {"offline", "newVideos", "noNew", "filteredN", "wlLogin", "wlOk", "wlFail", "hidden", "undo", "skipped",
                "showAll", "filteredAll"};
        for (int i = 0; i < ids.length; i++) {
            try { o.put(keys[i], c.getString(ids[i])); } catch (Exception ignored) {}
        }
        return o;
    }

    @JavascriptInterface
    public void mediaMeta(final String title, final String artist, final String art, final long durMs, final String videoId) {
        main.post(() -> PlaybackService.onMeta(app, title, artist, art, durMs, videoId));
    }

    /** Live stream or not (294), sent with the metadata. */
    @JavascriptInterface
    public void mediaLive(final boolean live) {
        main.post(() -> PlaybackService.setLive(live));
    }

    /** Sleep badge on the player tapped (309): one more step for a minutes timer, else the timer choices. */
    @JavascriptInterface
    public void sleepBadgeTap() {
        if (!allowed("sleepBadgeTap")) return;
        onUi(a -> {
            if (SleepTimer.timed()) SleepTimer.extend(a);
            else a.openSleepPicker();
        });
    }

    /** Sleep badge long-pressed (309): the timer choices, with "off". */
    @JavascriptInterface
    public void sleepBadgeLong() {
        if (!allowed("sleepBadgeLong")) return;
        onUi(MainActivity::openSleepPicker);
    }

    @JavascriptInterface
    public void mediaState(final String state, final long posMs, final float rate) {
        main.post(() -> {
            PlaybackService.onState(app, state, posMs, rate);
            MainActivity a = act();
            if (a != null) a.onPlayingChanged("playing".equals(state) || "buffering".equals(state));
        });
    }

    @JavascriptInterface
    public void setAtTop(final boolean top) {
        onUi(a -> a.setPageAtTop(top));
    }

    @JavascriptInterface
    public void setPortrait(final boolean portrait) {
        onUi(a -> a.portraitVideo = portrait);
    }

    /**
     * Methods that act on the app are refused while the main frame is a known non-YouTube page
     * (409): the Google sign-in pages share this WebView. Reading state stays open.
     */
    private boolean allowed(String what) {
        if (com.google.android.youtube.pro.Logic.bridgeAllowed(MainActivity.pageHost())) return true;
        if (Diag.allowFromPage()) Diag.log("bridge", "refused " + what + " on " + MainActivity.pageHost());
        return false;
    }

    @JavascriptInterface
    public void openSettings() {
        if (!allowed("openSettings")) return;
        onUi(MainActivity::openSettings);
    }

    /** Fresh reload after the script dropped YouTube's client-side caches. */
    @JavascriptInterface
    public void hardReload() {
        if (!allowed("hardReload")) return;
        onUi(MainActivity::hardReload);
    }

    @JavascriptInterface
    public void reloadPage() {
        if (!allowed("reloadPage")) return;
        onUi(MainActivity::hardReload);
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
                    app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.isActiveNetworkMetered();
        } catch (Exception e) {
            return false;
        }
    }

    /** Speed the user picked, remembered when that setting is on (30). */
    @JavascriptInterface
    public void saveRate(final String rate) {
        if (!allowed("saveRate")) return;
        try {
            double r = Double.parseDouble(rate);
            if (r >= 0.25 && r <= 2) Prefs.put(app, Prefs.RATE, String.valueOf(r));
        } catch (Exception ignored) {}
    }

    /** Last video + position, offered again when the app is reopened after being killed (27). */
    @JavascriptInterface
    public void savePos(final String videoId, final long sec, final String title) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{6,20}") || !allowed("savePos")) return;
        Prefs.get(app).edit()
                .putString(Prefs.LAST_VIDEO, videoId)
                .putLong(Prefs.LAST_POS, Math.max(0, sec))
                .putString(Prefs.LAST_TITLE, title == null ? "" : (title.length() > 120 ? title.substring(0, 120) : title))
                .putLong(Prefs.LAST_AT, System.currentTimeMillis())
                .apply();
    }

    /** Native playback tools sheet (sleep, black screen, touch lock, speed, loop...). */
    @JavascriptInterface
    public void openTools() {
        if (!allowed("openTools")) return;
        onUi(MainActivity::openTools);
    }

    /** Long-press on a video card (123): native quick menu. */
    @JavascriptInterface
    public void cardMenu(final String json) {
        if (json == null || json.length() > 2000 || !allowed("cardMenu")) return;
        onUi(a -> a.cardMenu(json));
    }

    /** Search box opened (124). */
    @JavascriptInterface
    public void showKeyboard() {
        if (!allowed("showKeyboard")) return;
        onUi(MainActivity::showKeyboard);
    }

    /** Page background colour for the system bars (92/145). */
    @JavascriptInterface
    public void pageTheme(final String color) {
        onUi(a -> a.applyTheme(color));
    }

    /** Remote rules (73): the cached, already verified rules.json, or "" (built-in rules only). */
    @JavascriptInterface
    public String getRemoteRules() {
        return com.google.android.youtube.pro.RemoteRules.cached(app);
    }

    /** Pull / Home tap while something plays in the mini player (317). */
    @JavascriptInterface
    public void confirmRefresh() {
        if (!allowed("confirmRefresh")) return;
        onUi(MainActivity::confirmRefresh);
    }

    /** The page saw the sign-in state or the account change (343): flush cookies now. */
    @JavascriptInterface
    public void loginChanged(final boolean signedIn) {
        main.post(() -> {
            try { android.webkit.CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
            Diag.log("login", signedIn ? "signed in / account changed: cookies flushed" : "signed out");
        });
    }

    /** Signed in or not, as the page sees it (346): "watch later" buttons only make sense signed in. */
    @JavascriptInterface
    public void loginState(final boolean signedIn) {
        main.post(() -> MainActivity.setSignedIn(signedIn));
    }

    /** First time home is opened signed out (347): one bar with a way to sign in. */
    @JavascriptInterface
    public void loginHint() {
        if (!allowed("loginHint")) return;
        onUi(MainActivity::showLoginHint);
    }

    /** Offline check for the refresh (318); unknown counts as online. */
    @JavascriptInterface
    public boolean isOnline() {
        MainActivity a = act();
        return a == null || a.isOnline();
    }

    /** A short message from the page: the app's own bar when it is on screen, a toast otherwise (356). */
    @JavascriptInterface
    public void toast(final String text) {
        if (text == null || text.length() > 300 || !allowed("toast")) return;
        main.post(() -> MainActivity.notifyText(app, text));
    }

    /** Same, for something that did not work: a "reject" vibration with it (372). */
    @JavascriptInterface
    public void toastFail(final String text) {
        if (text == null || text.length() > 300 || !allowed("toastFail")) return;
        main.post(() -> {
            MainActivity.notifyText(app, text);
            MainActivity.hapticNow(com.google.android.youtube.pro.Ui.HAPTIC_REJECT);
        });
    }

    /**
     * SponsorBlock segments, fetched natively (no CSP issues, off the UI thread). Asked by the first
     * 4 hex digits of sha256(videoId) (410): the service only learns a bucket of many videos, the
     * one being watched is picked out here.
     */
    @JavascriptInterface
    public void fetchSegments(final String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{6,20}")) return;
        fetchSegments(videoId, 0);
    }

    // SponsorBlock answers (286): the last 50 videos for 6 hours, so replays / back-and-forth cost nothing
    private static final int SB_CACHE = 50;
    private static final long SB_TTL = 6 * 3600_000L;
    private static final java.util.LinkedHashMap<String, Object[]> SB = new java.util.LinkedHashMap<String, Object[]>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Object[]> e) { return size() > SB_CACHE; }
    };

    private void fetchSegments(final String videoId, final int attempt) {
        final java.util.List<String> chosen = Prefs.sponsorCats(app);
        final String key = videoId + "|" + chosen;
        synchronized (SB) {
            Object[] hit = SB.get(key);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < SB_TTL) {
                final String js = "ytl.onSegments(" + JSONObject.quote(videoId) + "," + hit[1] + ")";
                main.post(() -> MainActivity.js(js));
                return;
            }
        }
        com.google.android.youtube.pro.Bg.NET.execute(() -> {
            JSONArray out = new JSONArray();
            HttpURLConnection c = null;
            boolean answered = false;   // a real answer (200 or 404 = "no segments") can be cached
            try {
                if (chosen.isEmpty()) throw new Exception("no categories");
                JSONArray catArr = new JSONArray();
                for (String k : chosen) catArr.put(k);
                String cats = URLEncoder.encode(catArr.toString(), "UTF-8");
                String prefix = com.google.android.youtube.pro.Logic.sbPrefix(videoId);
                if (prefix.length() != 4) throw new Exception("no hash");
                URL u = new URL("https://sponsor.ajay.app/api/skipSegments/" + prefix + "?categories=" + cats);
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("User-Agent", "YiWei");
                int code = c.getResponseCode();
                if (code == 404) answered = true;
                if (code == 200) {
                    JSONArray arr = new JSONArray(read(c.getInputStream()));
                    answered = true;
                    for (int v = 0; v < arr.length(); v++) {
                        JSONObject vo = arr.optJSONObject(v);
                        if (vo == null || !videoId.equals(vo.optString("videoID", ""))) continue;
                        JSONArray segs = vo.optJSONArray("segments");
                        if (segs == null) continue;
                        for (int i = 0; i < segs.length(); i++) {
                            JSONObject o = segs.getJSONObject(i);
                            JSONArray seg = o.getJSONArray("segment");
                            String cat = o.optString("category", "sponsor");
                            if ("skip".equals(o.optString("actionType", "skip")) && chosen.contains(cat)) {
                                out.put(new JSONArray().put(seg.getDouble(0)).put(seg.getDouble(1)).put(cat));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
            if (answered) {
                synchronized (SB) { SB.put(key, new Object[]{System.currentTimeMillis(), out.toString()}); }
            } else if (attempt == 0 && !chosen.isEmpty()) {
                // network hiccup: one more try 30 s later, only if that video is still the one playing
                main.postDelayed(() -> {
                    if (videoId.equals(PlaybackService.videoId())) fetchSegments(videoId, 1);
                }, 30_000L);
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
    public void logAd(final String videoId, final String type, final String page, final String context) {
        if (!Diag.allowFromPage()) return;
        // page context + what only the app knows (280): the native rewrite of the last watch page, WebView version
        Diag.ad(videoId, type, page, (context == null ? "" : context) + " wpf=" + WatchPageFilter.lastResult()
                + " wv=" + MainActivity.webViewVersionCached());
    }

    /** Recovery gave up / no network while playing (250): the notification says why it stopped. */
    @JavascriptInterface
    public void netStalled(final boolean on) {
        main.post(() -> PlaybackService.setNetNote(on));
    }

    /** The page's self check found a missing ad-blocking hook (254): told once per version. */
    @JavascriptInterface
    public void selfCheckFailed(final String what) {
        if (what == null || what.length() > 200) return;
        onUi(a -> a.onSelfCheckFailed(what));
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
