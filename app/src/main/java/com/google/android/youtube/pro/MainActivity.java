package com.google.android.youtube.pro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.youtube.pro.webview.WebAppInterface;
import com.google.android.youtube.pro.webview.YTProWebChromeClient;
import com.google.android.youtube.pro.webview.YTProWebView;
import com.google.android.youtube.pro.webview.YTProWebViewClient;
import com.google.android.youtube.pro.webview.WatchPageFilter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    public static final int REQ_MIC = 101;
    private static final int REQ_NOTIF = 102;
    private static final String HOME = "https://m.youtube.com/";

    private static WeakReference<MainActivity> current = new WeakReference<>(null);
    private static boolean visible = false;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    public static final Handler MAIN_HANDLER = MAIN;

    public boolean portraitVideo = false;

    private YTProWebView web;
    private SwipeRefreshLayout swipe;
    private YTProWebChromeClient chrome;
    private OnBackInvokedCallback backCallback;
    private String script = "";
    private boolean docStartScript = false;
    private boolean pageAtTop = true;
    private boolean touchStartedAtTop = false;
    private boolean noCacheLoad = false;
    private boolean clearHistoryOnFinish = false;
    private String currentUrl = HOME;

    // native top progress bar + offline / load-failure screen (overlay above the WebView)
    private ProgressBar topBar;
    private View errorPanel;
    private TextView errorText;
    private String failedUrl;
    private boolean loadError = false;
    private Object errNetCallback;
    private long lastTrim = 0;

    // batch 2: snack bar (night sleep prompt 18, resume 27), black screen (157), touch lock (131),
    // orientation lock (132), double back to exit (118)
    private FrameLayout overlayRoot;
    private LinearLayout snack;
    private TextView snackText;
    private Button snackAction;
    private final Runnable hideSnackRun = this::hideSnack;
    private View blackView;
    private View lockView;
    private float savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    private int orientationLock = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private long lastBackAt = 0;
    private boolean coldStart = false;

    // batch 3
    private static boolean sleepModeOn = false;     // one-tap sleep mode (97)
    private float sleepModeSavedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    private String pageTheme = "#0f0f0f";
    private android.content.BroadcastReceiver powerReceiver;
    public static final String ACTION_SLEEP_MODE = "com.yywgov.ytlite.SLEEP_MODE";

    // ===================== static helpers =====================

    public static boolean isVisible() { return visible; }

    /** There is a live page that can take commands (296). */
    public static boolean hasPage() {
        MainActivity a = current.get();
        return a != null && a.web != null && !a.isFinishing();
    }

    /** Host of the main frame as last reported by the WebView clients (409); "" when not known. */
    private static volatile String pageHost = "";
    public static String pageHost() { return pageHost; }

    private static volatile String webViewVersion = "";
    /** WebView package + version, read once (280). */
    public static String webViewVersionCached() { return webViewVersion; }

    // ---- commands for the page (247): queued while the page is (re)loading, run once it asks for its config ----
    private static final int QUEUE_MAX = 10;
    private static final long QUEUE_TTL_MS = 5000L;
    private static final java.util.ArrayDeque<Object[]> pending = new java.util.ArrayDeque<>();

    /** Run a snippet against the injected script (window.ytl) on the main thread. */
    public static void js(final String code) {
        Runnable r = () -> {
            MainActivity a = current.get();
            if (a == null || a.web == null) return;
            a.unfreezeWeb();   // any command wakes a page paused after the sleep timer (273)
            a.web.evaluateJavascript("(function(){if(!window.ytl)return 0;try{" + code + "}catch(e){try{ytl.report&&ytl.report("
                    + org.json.JSONObject.quote(code.length() > 40 ? code.substring(0, 40) : code) + ",e)}catch(x){}}return 1})()", res -> {
                if (!"0".equals(res)) return;
                // no script yet (reload in progress): keep it a few seconds for the new page
                synchronized (pending) {
                    if (pending.size() >= QUEUE_MAX) pending.pollFirst();
                    pending.addLast(new Object[]{code, android.os.SystemClock.elapsedRealtime()});
                }
            });
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else MAIN.post(r);
    }

    /** The page script asked for its config: it is there now, so queued commands run (247). */
    public static void onPageReady() {
        MAIN.post(() -> {
            java.util.List<String> run = new java.util.ArrayList<>();
            long now = android.os.SystemClock.elapsedRealtime();
            synchronized (pending) {
                for (Object[] o : pending) if (now - (Long) o[1] <= QUEUE_TTL_MS) run.add((String) o[0]);
                pending.clear();
            }
            if (run.isEmpty()) return;
            // after the script's start-up has run
            MAIN.postDelayed(() -> { for (String c : run) js(c); }, 300);
        });
    }

    // ---- after the sleep timer stopped playback and nothing happened for 5 min (273) ----
    private boolean webFrozen = false;

    public static void freezeWeb() {
        MainActivity a = current.get();
        if (a == null || a.web == null || a.webFrozen || visible) return;
        try {
            a.web.onPause();
            a.web.pauseTimers();
            a.webFrozen = true;
            Diag.log("sleep", "page timers paused 5 min after the timer ended");
        } catch (Throwable t) {
            Diag.log("sleep", "freeze failed: " + t);
        }
    }

    void unfreezeWeb() {
        if (!webFrozen || web == null) return;
        webFrozen = false;
        try {
            web.resumeTimers();
            web.onResume();
            Diag.log("sleep", "page timers resumed");
        } catch (Throwable t) {
            Diag.log("sleep", "resume failed: " + t);
        }
    }

    // ===================== lifecycle =====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diag.init(this);
        Prefs.migrate(this);   // settings schema (226): idempotent, never blocks the start
        WatchPageFilter.init(this);   // one ad-key list with the page script (279)
        if (webViewVersion.isEmpty()) webViewVersion = webViewVersion(this);
        current = new WeakReference<>(this);
        setContentView(R.layout.main);
        swipe = findViewById(R.id.swipe);
        web = findViewById(R.id.web);
        script = readAsset("ytlite.js");
        requestHighRefreshRate();
        setupWebView();
        applyTextZoom();
        setupSwipe();
        setupBack();
        setupOverlay();
        Diag.log("start", "v" + appVersion() + " docStartScript=" + docStartScript + " script=" + script.length());
        restoreOrientation();
        String startUrl = urlFromIntent(getIntent());
        coldStart = savedInstanceState == null && HOME.equals(startUrl);
        // start page (63): subscriptions instead of home when launched from the icon
        if (coldStart && "subs".equals(Prefs.str(this, Prefs.START_PAGE, "home"))) startUrl = HOME + "feed/subscriptions";
        if (getIntent() != null && getIntent().getBooleanExtra(PlaybackService.EXTRA_RESUME, false)) {
            String resume = lastVideoUrl();
            if (resume != null) { startUrl = resume; coldStart = false; }
        }
        // renderer crash / kill (242): back to the page and position it was on, not the start page
        if (rendererRestoreUrl != null) {
            startUrl = rendererRestoreUrl;
            rendererRestoreUrl = null;
            coldStart = false;
            Diag.log("renderer", "restored " + (isPlayerUrl(startUrl) ? "video" : "page"));
        }
        web.loadUrl(startUrl);
        SleepTimer.restore(this);   // a timer the system killed with the process comes back (244)
        if (ACTION_SLEEP_MODE.equals(getIntent().getAction())) MAIN.postDelayed(this::startSleepMode, 1500);
        registerPowerSave();
        MAIN.postDelayed(this::askPermissionsOnce, 1500);
        // the rest waits for the first page (261): one after another, a second apart
        MAIN.postDelayed(startupRun, 8000);   // no page finished (offline): run anyway
    }

    // ---- cold start queue (261) ----
    private boolean startupDone = false;
    private final Runnable startupRun = this::runStartupQueue;

    private void runStartupQueue() {
        if (startupDone || isFinishing()) return;
        startupDone = true;
        MAIN.removeCallbacks(startupRun);
        final boolean offer = coldStart;
        Runnable[] jobs = {
                this::maybeWhatsNew,
                () -> { if (offer) offerResume(); },
                this::checkNotifications,
                () -> { Updater.cleanup(this); Updater.autoCheck(this); },
                () -> RemoteRules.autoCheck(this),
        };
        for (int i = 0; i < jobs.length; i++) {
            final Runnable j = jobs[i];
            MAIN.postDelayed(() -> { if (!isFinishing()) { try { j.run(); } catch (Throwable t) { Diag.log("startup", String.valueOf(t)); } } }, 1000L * (i + 1));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        unfreezeWeb();
        requestHighRefreshRate();
        Updater.onResume(this);
        PlaybackService.retryForeground();   // a refused foreground start gets another try now (291)
        MAIN.removeCallbacks(nightPromptRun);
        MAIN.postDelayed(nightPromptRun, coldStart ? 7000 : 2500);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        requestHighRefreshRate();
    }

    // ===================== high refresh rate (120 Hz LTPO etc.) =====================

    /**
     * Ask for the display mode with the highest refresh rate at the current resolution.
     * LTPO panels (and ColorOS/MIUI) otherwise often keep WebView apps at 60 Hz.
     */
    private void requestHighRefreshRate() {
        try {
            @SuppressWarnings("deprecation")
            Display d = Build.VERSION.SDK_INT >= 30 ? getDisplay() : getWindowManager().getDefaultDisplay();
            if (d == null) return;
            Display.Mode cur = d.getMode();
            Display.Mode best = cur;
            for (Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() == cur.getPhysicalWidth()
                        && m.getPhysicalHeight() == cur.getPhysicalHeight()
                        && m.getRefreshRate() > best.getRefreshRate() + 0.5f) {
                    best = m;
                }
            }
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (lp.preferredDisplayModeId != best.getModeId() || lp.preferredRefreshRate != best.getRefreshRate()) {
                lp.preferredDisplayModeId = best.getModeId();
                lp.preferredRefreshRate = best.getRefreshRate();
                getWindow().setAttributes(lp);
            }
            if (Build.VERSION.SDK_INT >= 35) {
                getWindow().setFrameRatePowerSavingsBalanced(false);
                getWindow().setFrameRateBoostOnTouchEnabled(true);
                setWebFrameRate(true);
            }
        } catch (Throwable ignored) {}
    }

    // ---- Android 15+ (275): ask for the high frame rate only while touching / scrolling ----
    private boolean frameHigh = false;
    private final Runnable frameIdle = () -> {
        if (chrome != null && chrome.isFullscreen()) return;   // fullscreen video keeps its rate
        setWebFrameRate(false);
    };

    private void setWebFrameRate(boolean high) {
        if (Build.VERSION.SDK_INT < 35 || web == null || frameHigh == high) return;
        try {
            web.setRequestedFrameRate(high ? View.REQUESTED_FRAME_RATE_CATEGORY_HIGH : View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL);
            frameHigh = high;
        } catch (Throwable ignored) {}
    }

    // ---- refresh rate while scrolling (391): samples to tell whether the system holds the app at 60 Hz ----
    private static int rrSamples = 0, rrLow = 0;
    private static float rrMax = 0;
    private long lastRrSample = 0;

    private void sampleRefreshRate() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastRrSample < 500 || rrSamples >= 400) return;
        lastRrSample = now;
        try {
            @SuppressWarnings("deprecation")
            Display d = Build.VERSION.SDK_INT >= 30 ? getDisplay() : getWindowManager().getDefaultDisplay();
            if (d == null) return;
            if (rrMax == 0) for (Display.Mode m : d.getSupportedModes()) rrMax = Math.max(rrMax, m.getRefreshRate());
            rrSamples++;
            if (d.getRefreshRate() < rrMax - 5) rrLow++;
        } catch (Throwable ignored) {}
    }

    /** Scrolling has mostly run below the panel's best rate (391). */
    public boolean refreshLimited() { return Logic.refreshLimited(rrSamples, rrLow, rrMax); }

    public void openDisplaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
            Toast.makeText(this, "屏幕刷新率 → 应用：把 " + getString(R.string.app_name) + " 设为高刷", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            openAppDetails();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_SLEEP_MODE.equals(intent.getAction())) { startSleepMode(); return; }
        if (intent.getBooleanExtra(PlaybackService.EXTRA_RESUME, false)) {
            String resume = lastVideoUrl();
            if (resume != null && web != null) { web.loadUrl(resume); return; }
        }
        final String url = urlFromIntent(intent);
        boolean external = Intent.ACTION_VIEW.equals(intent.getAction()) || Intent.ACTION_SEND.equals(intent.getAction());
        if (!external || web == null) return;
        // something is playing (293): a link tapped in another app does not replace it without asking
        if (PlaybackService.isPlaying() && !url.equals(currentUrl)) {
            showSnack("正在播放，要打开收到的链接吗？", "打开", () -> { if (web != null) web.loadUrl(url); }, 10000);
            return;
        }
        web.loadUrl(url);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        unfreezeWeb();
        PlaybackService.noteInput();
        js("ytl.bg(false)");
        PlaybackService.refresh();   // Wi-Fi lock choice follows the screen (271)
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        js("ytl.bg(true)");
        CookieManager.getInstance().flush();
        PlaybackService.refresh();
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (current.get() == this) {
            if (isFinishing()) {
                // the user left the app (double back / finish): everything stops as before
                SleepTimer.cancel(null);
                PlaybackService.stop(this);
            } else {
                // destroyed by the system (243): don't cancel the timer or kill the service; the
                // notification turns into "tap to continue" (the WebView itself is gone)
                Diag.log("lifecycle", "activity destroyed by the system, playing=" + PlaybackService.isPlaying()
                        + " config=" + isChangingConfigurations());
                if (PlaybackService.isPlaying() && !isChangingConfigurations()) PlaybackService.onActivityGone();
            }
            current = new WeakReference<>(null);
        }
        MAIN.removeCallbacks(blackWatch);
        MAIN.removeCallbacks(nightPromptRun);
        MAIN.removeCallbacks(hideSnackRun);
        if (powerReceiver != null) { try { unregisterReceiver(powerReceiver); } catch (Exception ignored) {} powerReceiver = null; }
        unregisterErrNet();
        destroyWebView();
        super.onDestroy();
    }

    // ===================== WebView =====================

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        MAIN.post(this::applyClientHints);   // (344) only when a sign-in was refused before
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        // capabilities YouTube never needs (sign-in and playback don't use any of them)
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Google blocks sign-in from embedded WebViews ("This browser or app may not be secure")
        // based on the "; wv" token in the user agent – present as plain Chrome instead.
        s.setUserAgentString(s.getUserAgentString()
                .replace("; wv)", ")")
                .replaceAll("Version/\\d+(\\.\\d+)* ", ""));
        WatchPageFilter.userAgent = s.getUserAgentString();
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, Collections.<String>emptySet());
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false); // YouTube's own dark theme
        }

        web.setBackgroundColor(Ui.BG);
        if (Build.VERSION.SDK_INT >= 35) web.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        // keep the renderer important even when not visible (screen off)
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        ensureDarkThemeCookie(cm);

        web.addJavascriptInterface(new WebAppInterface(this), "YTL");
        chrome = new YTProWebChromeClient(this);
        web.setWebChromeClient(chrome);
        web.setWebViewClient(new YTProWebViewClient(this));

        // Bundled script, injected at document start, once per document.
        if (!script.isEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Set<String> origins = new HashSet<>();
            origins.add("https://m.youtube.com");
            origins.add("https://www.youtube.com");
            origins.add("https://youtube.com");
            try {
                WebViewCompat.addDocumentStartJavaScript(web, script, origins);
                docStartScript = true;
            } catch (Exception e) {
                docStartScript = false;
            }
        }
    }

    /** First run: ask YouTube for its dark theme (PREF f6=400) unless the user already chose one. */
    private void ensureDarkThemeCookie(CookieManager cm) {
        String c = cm.getCookie("https://m.youtube.com");
        if (c == null || !c.contains("PREF=")) {
            cm.setCookie("https://m.youtube.com", "PREF=f6=400; Domain=.youtube.com; Path=/; Max-Age=63072000; Secure");
        }
    }

    private void injectFallback() {
        if (!docStartScript && !script.isEmpty() && web != null) web.evaluateJavascript(script, null);
    }

    private void destroyWebView() {
        if (web == null) return;
        try {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.stopLoading();
            web.destroy();
        } catch (Exception ignored) {}
        web = null;
    }

    /** Page to reopen after the renderer died (242), read by the recreated activity. */
    private static String rendererRestoreUrl = null;

    public void onRendererGone() {
        try {
            // back to where it was: a video at its current position, any other page as it was
            String vid = PlaybackService.videoId();
            if (isPlayerUrl(currentUrl) && vid != null && vid.matches("[A-Za-z0-9_-]{6,20}")) {
                long pos = PlaybackService.positionNowMs() / 1000;
                rendererRestoreUrl = HOME + "watch?v=" + vid + (pos > 5 ? "&t=" + pos + "s" : "");
                Prefs.get(this).edit().putString(Prefs.LAST_VIDEO, vid).putLong(Prefs.LAST_POS, pos)
                        .putLong(Prefs.LAST_AT, System.currentTimeMillis()).apply();
            } else if (currentUrl != null && Logic.isYouTubeHost(currentUrl)) {
                rendererRestoreUrl = currentUrl;
            }
        } catch (Throwable t) {
            Diag.log("renderer", "restore info: " + t);
        }
        unregisterErrNet();
        destroyWebView();
        recreate();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // ===================== callbacks from the WebView clients / bridge =====================

    public void onPageStarted(String url) {
        currentUrl = url;
        pageHost = Logic.hostOf(url);
        pageAtTop = true;
        loadError = false;
        showTopBar(10);
    }

    public void onPageFinished(String url) {
        currentUrl = url;
        pageHost = Logic.hostOf(url);
        // Google sign-in pages: store the cookies as soon as each step finishes (343)
        if (url != null && url.startsWith("https://accounts.google.")) { flushCookies(); checkSignInRejected(url); }
        injectFallback();
        if (!startupDone) { MAIN.removeCallbacks(startupRun); MAIN.postDelayed(startupRun, 500); }   // (261)
        swipe.setRefreshing(false);
        hideTopBar();
        if (!loadError) hideError();
        if (noCacheLoad) restoreCacheMode();
        if (clearHistoryOnFinish && web != null) {
            clearHistoryOnFinish = false;
            web.clearHistory(); // refreshed home is the new root: back leaves the app
            haptic(Ui.HAPTIC_CONFIRM);   // refresh finished (120/372)
        }
    }

    /** First paint of the new page (253): the HTTP cache comes back on right away. */
    public void onPageCommitVisible() {
        if (noCacheLoad) restoreCacheMode();
    }

    public void onUrlChanged(String url) {
        currentUrl = url;
        pageHost = Logic.hostOf(url);
        if (!isPlayerUrl(url)) pageAtTop = web == null || web.getScrollY() == 0;
    }

    public void setPageAtTop(boolean top) {
        pageAtTop = top;
    }

    public void onPlayingChanged(boolean playing) {
        // nothing extra for now: the screen is allowed to time out while playing
        // (so you can fall asleep); fullscreen keeps it on via the chrome client.
    }

    /**
     * Pull-to-refresh / tap Home. Let the page script drop YouTube's client-side feed
     * caches first (it calls back hardReload); without the script, reload directly.
     */
    public void refreshFeed() {
        if (web == null) return;
        if (!isOnline()) {
            // offline (318): keep the list that is on screen instead of reloading into an error page
            swipe.setRefreshing(false);
            say("当前离线，已保留列表");
            return;
        }
        // the pull gesture's spinner goes away at once; the thin top bar shows the reload
        swipe.setRefreshing(false);
        showTopBar(5);
        web.evaluateJavascript("(window.ytl&&ytl.refresh)?(ytl.refresh()===false?2:1):0", r -> {
            if ("2".equals(r)) hideTopBar();          // offline or asking first (317/318): nothing reloads
            else if (!"1".equals(r)) hardReload();
        });
        MAIN.postDelayed(() -> { if (swipe != null) swipe.setRefreshing(false); }, 10000);
    }

    /** Reload the current page (home by default) bypassing the HTTP cache, with a cache-busting param. */
    public void hardReload() {
        if (web == null) return;
        showTopBar(5);
        String base = currentUrl == null || !currentUrl.startsWith("https://") || isPlayerUrl(currentUrl) ? HOME : currentUrl;
        Uri u = Uri.parse(base);
        Uri.Builder b = u.buildUpon().clearQuery();
        for (String k : u.getQueryParameterNames()) {
            if ("ytlr".equals(k)) continue;
            for (String v : u.getQueryParameters(k)) b.appendQueryParameter(k, v);
        }
        b.appendQueryParameter("ytlr", Long.toString(System.currentTimeMillis(), 36));
        String path = u.getPath();
        clearHistoryOnFinish = path == null || path.isEmpty() || "/".equals(path);
        web.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        noCacheLoad = true;
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-cache");
        h.put("Pragma", "no-cache");
        web.loadUrl(b.build().toString(), h);
        MAIN.removeCallbacks(restoreCache);
        MAIN.postDelayed(restoreCache, 20000);
        MAIN.postDelayed(() -> { if (swipe != null) swipe.setRefreshing(false); }, 10000);
    }

    private final Runnable restoreCache = this::restoreCacheMode;

    public boolean isOnline() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            @SuppressWarnings("deprecation")
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni == null || ni.isConnected();   // unknown counts as online
        } catch (Throwable t) {
            return true;
        }
    }

    /** Pull / Home tap while a video plays in the mini player (317): ask before stopping it. */
    public void confirmRefresh() {
        swipe.setRefreshing(false);
        hideTopBar();
        showSnack("刷新会停止正在播放的视频", "仍要刷新", () -> js("ytl.refresh(true)"), 6000);
    }

    // ---- sign-in refused as "this browser may not be secure" (344): only then send Chrome-like client hints ----
    private static final String REJECT_RE_JS = "(function(){try{var t=(document.title+' '+(document.body?document.body.innerText:'')).slice(0,3000);"
            + "return /may not be secure|浏览器或应用可能不安全|瀏覽器或應用程式可能不安全|此浏览器或应用可能不安全/i.test(t)?1:0}catch(e){return 0}})()";

    private void checkSignInRejected(String url) {
        if (web == null || Prefs.get(this).getBoolean(Prefs.CH_UA_FIX, false)) return;
        boolean byUrl = url.contains("/rejected");
        web.evaluateJavascript(REJECT_RE_JS, r -> {
            if (!byUrl && !"1".equals(r)) return;
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                Diag.log("login", "sign-in refused, client hints cannot be changed on this WebView");
                return;
            }
            Prefs.put(this, Prefs.CH_UA_FIX, true);
            applyClientHints();
            Diag.log("login", "sign-in refused as insecure: Chrome client hints on from now");
            Toast.makeText(this, "登录被拦截：已调整浏览器标识，请重新登录", Toast.LENGTH_LONG).show();
            if (web != null) web.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=" + Uri.encode(HOME));
        });
    }

    /** Sec-CH-UA brands like Chrome's instead of "Android WebView" (344); only after a refused sign-in. */
    private void applyClientHints() {
        try {
            if (web == null || !Prefs.get(this).getBoolean(Prefs.CH_UA_FIX, false)
                    || !WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return;
            WebSettings s = web.getSettings();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/(\\d+)(\\.[\\d.]+)?").matcher(s.getUserAgentString());
            String major = m.find() ? m.group(1) : "130";
            String full = major + (m.group(2) != null ? m.group(2) : ".0.0.0");
            java.util.List<androidx.webkit.UserAgentMetadata.BrandVersion> brands = new java.util.ArrayList<>();
            brands.add(new androidx.webkit.UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome").setMajorVersion(major).setFullVersion(full).build());
            brands.add(new androidx.webkit.UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(major).setFullVersion(full).build());
            brands.add(new androidx.webkit.UserAgentMetadata.BrandVersion.Builder().setBrand("Not?A_Brand").setMajorVersion("99").setFullVersion("99.0.0.0").build());
            androidx.webkit.UserAgentMetadata cur = WebSettingsCompat.getUserAgentMetadata(s);
            WebSettingsCompat.setUserAgentMetadata(s, new androidx.webkit.UserAgentMetadata.Builder(cur).setBrandVersionList(brands).setFullVersion(full).build());
        } catch (Throwable t) {
            Diag.log("login", "client hints: " + t);
        }
    }

    // ---- self check failed (254): one bar per version, with a way to send the report ----
    public void onSelfCheckFailed(String what) {
        try {
            String ver = appVersion();
            if (ver.equals(Prefs.str(this, Prefs.SELFCHECK_WARNED, "")) || isFinishing()) return;
            Prefs.put(this, Prefs.SELFCHECK_WARNED, ver);
            showSnack("去广告组件异常（" + what + "），已记录", "复制报告", () -> SettingsPage.copyReport(this), 10000);
        } catch (Throwable t) {
            Diag.log("selfcheck", String.valueOf(t));
        }
    }

    /** Signed in / account switched (343): write the cookies to disk now, not only in onStop. */
    public void flushCookies() {
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
    }

    /** Evaluate an expression in the page and get its JSON result (settings: hidden video count). */
    public void evalJs(String expr, android.webkit.ValueCallback<String> cb) {
        if (web == null) { cb.onReceiveValue(null); return; }
        try { web.evaluateJavascript("(function(){try{return " + expr + "}catch(e){return null}})()", cb); }
        catch (Throwable t) { cb.onReceiveValue(null); }
    }

    private String lastVideoUrl() {
        android.content.SharedPreferences p = Prefs.get(this);
        String id = p.getString(Prefs.LAST_VIDEO, null);
        if (id == null || !id.matches("[A-Za-z0-9_-]{6,20}")) return null;
        long pos = p.getLong(Prefs.LAST_POS, 0);
        return HOME + "watch?v=" + id + (pos > 5 ? "&t=" + pos + "s" : "");
    }

    // ===================== keep-alive checks (291/292) =====================

    /** Notifications allowed and the playback channel not switched off (292). */
    public boolean notificationsOk() {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return true;
            if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return false;
            if (Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = nm.getNotificationChannel(PlaybackService.CHANNEL_ID);
                if (ch != null && ch.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE) return false;
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private void checkNotifications() {
        if (!isFinishing() && !notificationsOk()) Diag.log("notif", "notifications or playback channel off");
    }

    public void openNotificationSettings() {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 26) {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            }
            startActivity(i);
        } catch (Exception e) {
            openAppDetails();
        }
    }

    /** Last refused foreground start within 3 days (291), 0 if none. */
    public long fgsDeniedAt() {
        long at = Prefs.get(this).getLong(Prefs.FGS_DENIED_AT, 0);
        return at > 0 && System.currentTimeMillis() - at < 3 * 24 * 3600_000L ? at : 0;
    }

    public void clearFgsDenied() {
        Prefs.get(this).edit().remove(Prefs.FGS_DENIED_AT).apply();
    }

    // ===================== keep the screen on: fullscreen and black screen share one flag (337) =====================

    private final Set<String> screenOnHolders = new HashSet<>();

    public void keepScreenOn(String who, boolean on) {
        if (on) screenOnHolders.add(who); else screenOnHolders.remove(who);
        try {
            if (screenOnHolders.isEmpty()) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) {}
    }

    private void restoreCacheMode() {
        noCacheLoad = false;
        if (web != null) web.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
    }

    public void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            say("无法打开链接");
        }
    }

    public void openSettings() {
        new SettingsPage(this).show();
    }

    /** A setting changed: push it to the page, the notification and the native bits that follow it. */
    public void applySettings() {
        js("ytl.cfg(" + Prefs.jsConfig(this) + ")");
        PlaybackService.refresh();
        applyTextZoom();
        applyTheme(pageTheme);
        if (swipe != null) swipe.setColorSchemeColors(Ui.accent(this));
    }

    /** Load a YouTube page inside the app (settings links). */
    public void loadInApp(String url) {
        if (web != null && url != null && url.startsWith("https://m.youtube.com/")) web.loadUrl(url);
    }

    // ===================== batch 3: theme bars (92/145), font (93), haptics (120), keyboard (124) =====================

    /** Page background reported by the script: system bars and the area behind them follow it. */
    @SuppressWarnings("deprecation")
    public void applyTheme(String color) {
        if (color == null || !color.matches("#[0-9a-fA-F]{6}")) return;
        pageTheme = color.toLowerCase(java.util.Locale.ROOT);
        try {
            boolean light = "#ffffff".equals(pageTheme);
            int c = light ? Ui.WHITE : (Prefs.on(this, Prefs.AMOLED) ? Ui.BLACK : android.graphics.Color.parseColor(pageTheme));
            if (swipe != null) swipe.setBackgroundColor(c);
            if (web != null) web.setBackgroundColor(c);
            getWindow().getDecorView().setBackgroundColor(c);
            if (Build.VERSION.SDK_INT < 35) {
                getWindow().setStatusBarColor(c);
                getWindow().setNavigationBarColor(c);
            }
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController ic = getWindow().getInsetsController();
                if (ic != null) {
                    int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    ic.setSystemBarsAppearance(light ? mask : 0, mask);
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                View d = getWindow().getDecorView();
                int f = d.getSystemUiVisibility();
                f = light ? (f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                if (Build.VERSION.SDK_INT >= 26) f = light ? (f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                d.setSystemUiVisibility(f);
            }
        } catch (Throwable t) {
            Diag.log("theme", String.valueOf(t));
        }
    }

    /** Web text follows the system font scale (93), clamped so layouts never overflow (142). */
    private void applyTextZoom() {
        if (web == null) return;
        int z = 100;
        if (Prefs.on(this, Prefs.FONT_FOLLOW)) {
            float fs = getResources().getConfiguration().fontScale;
            int max = Prefs.on(this, Prefs.FONT_LARGE) ? 160 : 130;   // very large text (378)
            z = Math.max(85, Math.min(max, Math.round(fs * 100)));
        }
        if (web.getSettings().getTextZoom() != z) web.getSettings().setTextZoom(z);
    }

    /** Light tap for key actions (120). */
    public void haptic() { haptic(Ui.HAPTIC_TAP); }

    /** Kind of tap (372): confirm / reject / long press feel different on Android 11+. */
    public void haptic(int kind) {
        try {
            if (!Prefs.on(this, Prefs.HAPTIC) || web == null) return;
            web.performHapticFeedback(Ui.hapticConstant(kind));
        } catch (Throwable ignored) {}
    }

    public static void hapticNow() { hapticNow(Ui.HAPTIC_TAP); }

    public static void hapticNow(final int kind) {
        MAIN.post(() -> { MainActivity a = current.get(); if (a != null) a.haptic(kind); });
    }

    public void showKeyboard() {
        try {
            if (web == null) return;
            web.requestFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(web, 0);
        } catch (Throwable ignored) {}
    }

    // ===================== long-press card menu (123) =====================

    public void cardMenu(String json) {
        final String id, title, channel, key;
        final boolean wl;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            id = o.optString("id", "");
            title = o.optString("title", "");
            channel = o.optString("channel", "");
            String k = o.optString("key", "");
            key = k.matches("@[^\\s|]{1,58}|UC[\\w-]{10,40}") ? k : "";
            wl = o.optBoolean("wl", false);
        } catch (Exception e) {
            return;
        }
        if (!id.matches("[A-Za-z0-9_-]{6,20}") || isFinishing()) return;
        haptic(Ui.HAPTIC_LONG);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView head = Ui.text(this, title.isEmpty() ? "视频" : title, 14, Ui.TEXT_2);
        head.setMaxLines(2);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.setPadding(dp(20), dp(4), dp(20), dp(8));
        box.addView(head);
        final android.app.Dialog[] d = new android.app.Dialog[1];
        // already in "watch later" (331): adding it again makes no sense there
        // signed out (346): the row would only say "sign in first"
        if (!wl && signedIn) box.addView(Ui.sheetRow(this, R.drawable.ic_watch_later_white, "稍后观看", "加入 YouTube 的「稍后观看」（需登录）", v -> { d[0].dismiss(); js("ytl.watchLater(" + org.json.JSONObject.quote(id) + ")"); }));
        box.addView(Ui.sheetRow(this, R.drawable.ic_more_white, wl ? "移除 / 更多" : "不感兴趣 / 更多", "打开 YouTube 自己的菜单", v -> { d[0].dismiss(); js("ytl.openCardMenu()"); }));
        if (!channel.isEmpty() || !key.isEmpty()) {
            final String label = channel.isEmpty() ? key : channel;
            box.addView(Ui.sheetRow(this, R.drawable.ic_block_white, "不再显示此频道", label + " · 只在本机屏蔽，可在设置里撤销", v -> {
                d[0].dismiss();
                // with the channel link (322): still matches after a rename, never a namesake
                Prefs.addLine(this, Prefs.BLOCK_CHANNELS, key.isEmpty() ? channel : key + "|" + channel);
                applySettings();
                say("已屏蔽：" + label);
            }));
        }
        box.addView(Ui.sheetRow(this, R.drawable.ic_hide_white, "本地隐藏这个视频", "以后在列表里不再出现", v -> { d[0].dismiss(); js("ytl.hideVideo(" + org.json.JSONObject.quote(id) + ")"); }));
        box.addView(Ui.sheetRow(this, R.drawable.ic_link_white, "复制链接", "https://youtu.be/" + id, v -> {
            d[0].dismiss();
            try {
                ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(android.content.ClipData.newPlainText("YouTube", "https://youtu.be/" + id));
                say("已复制链接");
            } catch (Exception ignored) {}
        }));
        d[0] = Ui.sheet(this, box);
        d[0].show();
    }

    // ===================== one-tap sleep mode (97) =====================

    public static boolean sleepModeOn() { return sleepModeOn; }

    // ---- signed in? (346) as the page reports it; unknown counts as signed in ----
    private static volatile boolean signedIn = true;

    public static boolean signedIn() { return signedIn; }

    public static void setSignedIn(boolean on) {
        if (signedIn == on) return;
        signedIn = on;
        PlaybackService.refresh();   // the notification's "watch later" button follows
    }

    /** First time home is opened signed out (347): one bar, never again. */
    public void showLoginHint() {
        if (!visible || snackShowing() || blackView != null || lockView != null) return;
        showSnack("登录 YouTube 后可用订阅、观看记录和稍后观看", "去登录", () -> {
            if (web != null) web.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=" + Uri.encode(HOME));
        }, 9000);
    }

    /**
     * A short message for the user (356): the app's own bar when it is in front with nothing over it
     * (a settings page or a sheet takes the window focus), a system toast otherwise.
     */
    public static void notifyText(android.content.Context c, String text) {
        MainActivity a = current.get();
        if (a != null && a.canSay()) { a.say(text); return; }
        if (c != null) Toast.makeText(c.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }

    private boolean canSay() {
        return visible && !isFinishing() && hasWindowFocus() && snack != null && blackView == null;
    }

    /** Plain message in the snack bar, or a toast when the bar can't be seen right now. */
    public void say(String text) {
        if (canSay()) showSnack(text, null, null, 2600);
        else Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }

    /** Timer (default length) + lowest quality + same channel only + dim screen; ends with the timer. */
    public void startSleepMode() {
        int m = Prefs.sleepDefault(this);
        sleepModeOn = true;
        if (!SleepTimer.active()) SleepTimer.start(this, m);
        js("ytl.sleepMode(true)");
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (lp.screenBrightness != 0.08f) sleepModeSavedBrightness = lp.screenBrightness;
            lp.screenBrightness = 0.08f;
            getWindow().setAttributes(lp);
        } catch (Throwable ignored) {}
        haptic();
        // optional black screen 10 s later (306), only if still in sleep mode and on screen
        MAIN.removeCallbacks(sleepModeBlackRun);
        if (Prefs.on(this, Prefs.SLEEP_MODE_BLACK)) {
            say("10 秒后进入黑屏，双击屏幕恢复");
            MAIN.postDelayed(sleepModeBlackRun, 10_000L);
        }
    }

    private final Runnable sleepModeBlackRun = () -> {
        if (sleepModeOn && visible && !isFinishing() && blackView == null) startBlackScreen();
    };

    /** Sleep timer choices (309): from the badge on the player. */
    public void openSleepPicker() {
        if (isFinishing()) return;
        PlayerTools.pickSleep(this, "睡眠定时", m -> SleepTimer.start(this, m));
    }

    private void stopSleepMode() {
        MAIN.removeCallbacks(sleepModeBlackRun);
        if (!sleepModeOn) return;
        sleepModeOn = false;
        js("ytl.sleepMode(false)");
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = sleepModeSavedBrightness;
            getWindow().setAttributes(lp);
        } catch (Throwable ignored) {}
    }

    /** Sleep timer started / stopped / fired. */
    public static void onSleepState(final boolean active) {
        MAIN.post(() -> {
            MainActivity a = current.get();
            if (a != null && !active) a.stopSleepMode();
        });
    }

    // ===================== battery saver (147), autostart (99), what's new (138), about (140) =====================

    private void registerPowerSave() {
        if (Build.VERSION.SDK_INT < 21) return;
        try {
            powerReceiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, Intent i) {
                    js("ytl.cfg(" + Prefs.jsConfig(MainActivity.this) + ")");
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(powerReceiver, f, RECEIVER_NOT_EXPORTED);
            else registerReceiver(powerReceiver, f);
        } catch (Throwable t) {
            powerReceiver = null;
        }
    }

    /** ColorOS / other vendor auto-start managers, falling back to app details (99). */
    public void openAutostartSettings() {
        String[][] targets = {
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent().setComponent(new android.content.ComponentName(t[0], t[1])).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (getPackageManager().resolveActivity(i, 0) == null) continue;
                startActivity(i);
                Toast.makeText(this, "找到 " + getString(R.string.app_name) + " 并允许自启动", Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) {}
        }
        openAppDetails();
    }

    /**
     * ColorOS 14/15 "耗电管理 → 允许后台运行" page for this app (390), tried component by component;
     * falls back to the app details page.
     */
    public void openBatterySettings() {
        String[][] targets = {
                {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerAppsBgSetting"},
                {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity"},
                {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"},
                {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerAppsBgSetting"},
                {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"},
                {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"},
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent().setComponent(new android.content.ComponentName(t[0], t[1]))
                        .putExtra("pkgName", getPackageName()).putExtra("package_name", getPackageName())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (getPackageManager().resolveActivity(i, 0) == null) continue;
                startActivity(i);
                Toast.makeText(this, "找到 " + getString(R.string.app_name) + "，设为「允许后台运行」", Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) {}
        }
        Toast.makeText(this, "耗电管理 → 允许后台运行", Toast.LENGTH_LONG).show();
        openAppDetails();
    }

    /** First start after an upgrade: show this version's notes once (138). */
    private void maybeWhatsNew() {
        try {
            if (isFinishing()) return;
            String ver = appVersion();
            String last = Prefs.str(this, Prefs.LAST_RUN_VERSION, "");
            if (ver.equals(last)) return;
            Prefs.put(this, Prefs.LAST_RUN_VERSION, ver);
            if (last.isEmpty() || ver.contains("-dev")) return;   // fresh install or test build: nothing to show
            Prefs.put(this, Prefs.UPDATE_READY, "");
            Updater.showWhatsNew(this, ver);
        } catch (Throwable t) {
            Diag.log("whatsnew", String.valueOf(t));
        }
    }

    public static String webViewVersion(android.content.Context c) {
        try {
            android.content.pm.PackageInfo p = WebViewCompat.getCurrentWebViewPackage(c);
            return p == null ? "未知" : p.packageName + " " + p.versionName;
        } catch (Throwable t) {
            return "未知";
        }
    }

    public String refreshRateLabel() {
        try {
            @SuppressWarnings("deprecation")
            Display d = Build.VERSION.SDK_INT >= 30 ? getDisplay() : getWindowManager().getDefaultDisplay();
            if (d == null) return "未知";
            float max = 0;
            if (Build.VERSION.SDK_INT >= 23) for (Display.Mode m : d.getSupportedModes()) max = Math.max(max, m.getRefreshRate());
            return String.format(java.util.Locale.US, "当前 %.0f Hz · 最高 %.0f Hz", d.getRefreshRate(), Math.max(max, d.getRefreshRate()));
        } catch (Throwable t) {
            return "未知";
        }
    }

    /** Moon button: playback tools sheet, with the page's current player state. */
    public void openTools() {
        toolState(st -> { if (!isFinishing()) PlayerTools.show(this, st); });
    }

    public interface StateCb { void on(org.json.JSONObject st); }

    /** The page's player state for the tools sheet (also re-read after a toggle, 335). */
    public void toolState(final StateCb cb) {
        if (web == null) { cb.on(null); return; }
        web.evaluateJavascript("(window.ytl&&ytl.state)?ytl.state():''", r -> {
            org.json.JSONObject st = null;
            try {
                if (r != null && r.length() > 2 && !"null".equals(r)) {
                    String json = new org.json.JSONTokener(r).nextValue().toString();
                    st = new org.json.JSONObject(json);
                }
            } catch (Exception ignored) {}
            cb.on(st);
        });
    }

    // ===================== snack bar: night sleep prompt (18), continue after a kill (27) =====================

    // plain messages wait behind a bar that offers an action (356), at most a few
    private final java.util.ArrayDeque<String> snackQueue = new java.util.ArrayDeque<>();
    private boolean snackHasAction = false;

    private void showSnack(String text, String action, final Runnable onAction, long ms) {
        if (snack == null) return;
        if (action == null && snackShowing() && snackHasAction) {
            if (snackQueue.size() < 3 && !snackQueue.contains(text)) snackQueue.addLast(text);
            return;
        }
        snack.animate().cancel();
        snackText.setText(text);
        snackHasAction = action != null;
        if (action != null) {
            snackAction.setText(action);
            snackAction.setVisibility(View.VISIBLE);
            // the bar starts leaving the moment its action is pressed (366)
            snackAction.setOnClickListener(v -> { hideSnack(); if (onAction != null) onAction.run(); });
        } else snackAction.setVisibility(View.GONE);
        snack.setVisibility(View.VISIBLE);
        snack.setAlpha(0f);
        snack.setTranslationY(dp(8));
        snack.animate().alpha(1f).translationY(0f).setDuration(Ui.anim(this, Ui.ANIM_MS)).start();
        snack.announceForAccessibility(text);   // (375)
        MAIN.removeCallbacks(hideSnackRun);
        MAIN.postDelayed(hideSnackRun, ms);
    }

    private boolean snackShowing() {
        return snack != null && snack.getVisibility() == View.VISIBLE;
    }

    /** 150 ms fade and 8 dp drop, then gone (366); a queued message follows. */
    private void hideSnack() {
        MAIN.removeCallbacks(hideSnackRun);
        if (snack == null || snack.getVisibility() != View.VISIBLE) { snackHasAction = false; return; }
        snackHasAction = false;
        snack.animate().cancel();
        snack.animate().alpha(0f).translationY(dp(8)).setDuration(Ui.anim(this, 150)).withEndAction(() -> {
            if (snack == null) return;
            snack.setVisibility(View.GONE);
            snack.setTranslationY(0f);
            String next = snackQueue.pollFirst();
            if (next != null && !isFinishing()) showSnack(next, null, null, 2600);
        }).start();
    }

    private final Runnable nightPromptRun = this::maybeNightPrompt;

    /** Night window from settings (307, default 22:00-05:00), timer off, once per night: offer the usual sleep timer (18). */
    private void maybeNightPrompt() {
        try {
            if (isFinishing() || !visible || SleepTimer.active() || snackShowing() || blackView != null || lockView != null) return;
            if (!Prefs.on(this, Prefs.NIGHT_PROMPT)) return;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int ns = Prefs.num(this, Prefs.NIGHT_START), ne = Prefs.num(this, Prefs.NIGHT_END);
            if (!Logic.inNight(h, ns, ne)) return;
            if (Logic.nightBelongsToYesterday(h, ns, ne)) cal.add(java.util.Calendar.DAY_OF_MONTH, -1);
            String night = cal.get(java.util.Calendar.YEAR) + "-" + cal.get(java.util.Calendar.DAY_OF_YEAR);
            if (night.equals(Prefs.str(this, Prefs.NIGHT_SHOWN, ""))) return;
            Prefs.put(this, Prefs.NIGHT_SHOWN, night);
            final int m = Prefs.sleepDefault(this);
            showSnack("夜深了，要开睡眠定时吗？", SleepTimer.optionLabel(m), () -> SleepTimer.start(this, m), 8000);
        } catch (Throwable t) {
            Diag.log("night", String.valueOf(t));
        }
    }

    /** Cold start from the launcher: offer the video that was playing when the app was last closed (27). */
    private void offerResume() {
        try {
            if (isFinishing() || !Prefs.on(this, Prefs.RESUME_PROMPT)) return;
            android.content.SharedPreferences p = Prefs.get(this);
            final String id = p.getString(Prefs.LAST_VIDEO, null);
            long at = p.getLong(Prefs.LAST_AT, 0), pos = p.getLong(Prefs.LAST_POS, 0);
            long age = System.currentTimeMillis() - at;
            if (id == null || at <= 0 || age < 0 || age > 12 * 3600_000L || pos < 10) return;
            if (currentUrl != null && isPlayerUrl(currentUrl)) return;
            String title = p.getString(Prefs.LAST_TITLE, "");
            final long fpos = pos;
            showSnack("继续播放：" + (title == null || title.isEmpty() ? "上次的视频" : title), "继续", () -> {
                if (web != null) web.loadUrl(HOME + "watch?v=" + id + "&t=" + fpos + "s");
            }, 9000);
        } catch (Throwable t) {
            Diag.log("resume", String.valueOf(t));
        }
    }

    private void clearLastVideo() {
        Prefs.get(this).edit().remove(Prefs.LAST_VIDEO).remove(Prefs.LAST_POS).remove(Prefs.LAST_AT).remove(Prefs.LAST_TITLE).apply();
    }

    // ===================== black screen (157) and touch lock (131) =====================

    private FrameLayout decor() {
        return (FrameLayout) getWindow().getDecorView();
    }

    // black screen without a sleep timer (274): leave after 5 min paused or 2 h, so the screen can sleep
    private static final long BLACK_PAUSED_MS = 5 * 60_000L;
    private static final long BLACK_MAX_MS = 2 * 3600_000L;
    private long blackSince = 0, blackPausedSince = 0, lastBlackTap = 0;
    private TextView blackHint;
    private final Runnable blackWatch = new Runnable() {
        @Override public void run() {
            if (blackView == null) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (SleepTimer.active()) {
                blackPausedSince = 0;
            } else if (now - blackSince > BLACK_MAX_MS) {
                Diag.log("black", "no timer: left after 2 h");
                stopBlackScreen();
                return;
            } else if (PlaybackService.isPlaying()) {
                blackPausedSince = 0;
            } else if (blackPausedSince == 0) {
                blackPausedSince = now;
            } else if (now - blackPausedSince > BLACK_PAUSED_MS) {
                Diag.log("black", "no timer: left after 5 min paused");
                stopBlackScreen();
                return;
            }
            MAIN.postDelayed(this, 60_000L);
        }
    };

    @SuppressWarnings("deprecation")
    public void startBlackScreen() {
        if (blackView != null) return;
        try {
            FrameLayout v = new FrameLayout(this);
            v.setBackgroundColor(Ui.BLACK);
            v.setClickable(true);
            // double tap leaves (370): turning over in bed and brushing the screen does not light it up
            v.setOnClickListener(x -> {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastBlackTap < 400) { stopBlackScreen(); return; }
                lastBlackTap = now;
                if (blackHint != null) {
                    blackHint.animate().cancel();
                    blackHint.setAlpha(1f);
                    blackHint.animate().alpha(0f).setStartDelay(1500).setDuration(Ui.anim(this, 600)).start();
                }
            });
            TextView hint = new TextView(this);
            hint.setText("黑屏播放中 · 双击恢复");
            hint.setTextColor(Ui.BLACK_HINT);   // readable on black (377)
            hint.setTextSize(14);
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER);
            v.addView(hint, hp);
            hint.animate().alpha(0f).setStartDelay(2500).setDuration(Ui.anim(this, 800)).start();
            blackHint = hint;
            decor().addView(v, new FrameLayout.LayoutParams(-1, -1));
            v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            savedBrightness = lp.screenBrightness;
            lp.screenBrightness = 0.01f;
            getWindow().setAttributes(lp);
            keepScreenOn("black", true);
            blackView = v;
            blackSince = android.os.SystemClock.elapsedRealtime();
            blackPausedSince = 0;
            MAIN.removeCallbacks(blackWatch);
            MAIN.postDelayed(blackWatch, 60_000L);
            if (!SleepTimer.active()) {
                Toast.makeText(this, "建议同时开睡眠定时；没开时暂停 5 分钟或播放 2 小时后自动退出黑屏", Toast.LENGTH_LONG).show();
            }
            // screen readers (375): say what happened, and "restore" is one action away
            v.setContentDescription("黑屏播放中");
            Ui.a11yAction(v, "恢复", this::stopBlackScreen);
            v.announceForAccessibility("黑屏播放中，双击两次屏幕或用操作菜单恢复");
        } catch (Throwable t) {
            Diag.log("black", String.valueOf(t));
        }
    }

    public void stopBlackScreen() {
        MAIN.removeCallbacks(blackWatch);
        if (blackView == null) return;
        try {
            decor().removeView(blackView);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = savedBrightness;
            getWindow().setAttributes(lp);
        } catch (Throwable ignored) {}
        keepScreenOn("black", false);
        blackView = null;
        blackHint = null;
    }

    /** Sleep timer finished: leave black screen so the display can time out normally. */
    public static void onSleepEnded() {
        MAIN.post(() -> {
            MainActivity a = current.get();
            if (a != null) a.stopBlackScreen();
        });
    }

    public void startTouchLock() {
        if (lockView != null) return;
        try {
            final FrameLayout v = new FrameLayout(this);
            v.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            // the hint pill: a ring that fills during the 1.5 s hold (369), then the text
            final LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(android.view.Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(12), dp(8), dp(14), dp(8));
            pill.setBackground(Ui.round(Ui.OVERLAY_PILL, dp(18)));
            final Ui.Ring ring = new Ui.Ring(this, Ui.accent(this));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(18), dp(18));
            rp.rightMargin = dp(8);
            pill.addView(ring, rp);
            final TextView label = new TextView(this);
            label.setText("触控已锁定 · 长按 1.5 秒解锁");
            label.setTextColor(Ui.TEXT);
            label.setTextSize(13);
            pill.addView(label);
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            pp.topMargin = safeTop() + dp(12);   // below the punch hole / status bar (389)
            v.addView(pill, pp);
            pill.animate().alpha(0.15f).setStartDelay(2500).setDuration(Ui.anim(this, 600)).start();
            final Runnable unlock = this::stopTouchLock;
            v.setOnTouchListener((x, ev) -> {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pill.animate().cancel();
                        pill.setAlpha(1f);
                        label.setText("继续按住解锁…");
                        ring.run(Ui.anim(this, 1500));
                        MAIN.postDelayed(unlock, 1500);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        MAIN.removeCallbacks(unlock);
                        ring.reset();
                        label.setText("触控已锁定 · 长按 1.5 秒解锁");
                        pill.animate().alpha(0.15f).setStartDelay(1500).setDuration(Ui.anim(this, 600)).start();
                        break;
                    default:
                        break;
                }
                return true; // swallow everything
            });
            // screen readers (375): touches are swallowed, so unlocking is an accessibility action / click
            v.setContentDescription("触控已锁定");
            v.setOnClickListener(x -> stopTouchLock());
            Ui.a11yAction(v, "解锁", this::stopTouchLock);
            decor().addView(v, new FrameLayout.LayoutParams(-1, -1));
            lockView = v;
            v.announceForAccessibility("触控已锁定，长按屏幕 1.5 秒解锁");
        } catch (Throwable t) {
            Diag.log("lock", String.valueOf(t));
        }
    }

    /** Top of the area no camera hole / status bar covers (389), in px. */
    private int safeTop() {
        try {
            android.view.WindowInsets wi = getWindow().getDecorView().getRootWindowInsets();
            if (wi == null) return dp(36);
            int top = wi.getStableInsetTop();
            if (wi.getDisplayCutout() != null) top = Math.max(top, wi.getDisplayCutout().getSafeInsetTop());
            return Math.max(top, dp(24));
        } catch (Throwable t) {
            return dp(36);
        }
    }

    public void stopTouchLock() {
        if (lockView == null) return;
        try { decor().removeView(lockView); } catch (Throwable ignored) {}
        lockView = null;
        haptic(Ui.HAPTIC_CONFIRM);   // (369/372)
        say("已解锁");
    }

    /** Fullscreen video was added to the decor view: keep our overlays above it. */
    public void raiseOverlays() {
        if (fsTools != null) fsTools.bringToFront();
        if (lockView != null) lockView.bringToFront();
        if (blackView != null) blackView.bringToFront();
    }

    // ===================== orientation lock (132) =====================

    public int orientationLock() { return orientationLock; }

    /** Remembered lock (388): 0 follow system, 1 portrait, 2 landscape. */
    private void restoreOrientation() {
        if (!Prefs.on(this, Prefs.ORIENT_REMEMBER)) return;
        int v = Prefs.num(this, Prefs.ORIENT_LOCK);
        orientationLock = v == 1 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : v == 2 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if (orientationLock != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) setRequestedOrientation(orientationLock);
    }

    public void setOrientRemember(boolean on) {
        Prefs.put(this, Prefs.ORIENT_REMEMBER, on);
        saveOrientation();
        say(on ? "下次打开沿用当前屏幕方向" : "下次打开跟随系统");
    }

    private void saveOrientation() {
        if (!Prefs.on(this, Prefs.ORIENT_REMEMBER)) return;
        int v = orientationLock == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ? 1
                : orientationLock == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ? 2 : 0;
        Prefs.put(this, Prefs.ORIENT_LOCK, v);
    }

    public void setOrientationLock(int o) {
        orientationLock = o;
        saveOrientation();
        if (chrome == null || !chrome.isFullscreen()) setRequestedOrientation(o);
        say(o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED ? "屏幕方向：跟随系统"
                : o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ? "已锁定竖屏" : "已锁定横屏");
    }

    // ===================== top progress bar + offline screen (112/113) =====================

    private void setupOverlay() {
        try {
            FrameLayout overlay = new FrameLayout(this);
            overlay.setFitsSystemWindows(true);

            topBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            topBar.setMax(100);
            topBar.setIndeterminate(false);
            if (Build.VERSION.SDK_INT >= 21) {
                topBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.accent(this)));   // brand colour (351)
                topBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
            }
            topBar.setVisibility(View.GONE);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
            bp.gravity = android.view.Gravity.TOP;
            overlay.addView(topBar, bp);

            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setGravity(android.view.Gravity.CENTER);
            panel.setBackgroundColor(Ui.BG);
            panel.setClickable(true); // swallow touches while shown
            panel.setPadding(dp(32), dp(32), dp(32), dp(32));
            // offline page (357): a line icon, the reason, one filled "retry" and a way to the network settings
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageResource(R.drawable.ic_wifi_off_white);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT_2));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(56), dp(56));
            ip.bottomMargin = dp(16);
            panel.addView(icon, ip);
            TextView title = new TextView(this);
            title.setText("无法连接到 YouTube");
            title.setTextColor(Ui.TEXT);
            title.setTextSize(18);
            title.setGravity(android.view.Gravity.CENTER);
            if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
            panel.addView(title);
            errorText = new TextView(this);
            errorText.setTextColor(Ui.TEXT_2);
            errorText.setTextSize(14);
            errorText.setGravity(android.view.Gravity.CENTER);
            errorText.setPadding(0, dp(8), 0, dp(20));
            panel.addView(errorText);
            Button retry = Ui.filledButton(this, "重试");
            retry.setOnClickListener(v -> retryLoad());
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(200), dp(48));
            panel.addView(retry, rlp);
            Button netSettings = Ui.textButton(this, "网络设置");
            netSettings.setOnClickListener(v -> openNetworkSettings());
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(200), dp(48));
            nlp.topMargin = dp(8);
            panel.addView(netSettings, nlp);
            panel.setVisibility(View.GONE);
            errorPanel = panel;
            overlay.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout sb = new LinearLayout(this);
            sb.setOrientation(LinearLayout.HORIZONTAL);
            sb.setGravity(android.view.Gravity.CENTER_VERTICAL);
            sb.setPadding(dp(16), dp(6), dp(6), dp(6));
            sb.setBackground(Ui.round(Ui.SNACK, dp(10)));
            sb.setElevation(dp(6));
            snackText = new TextView(this);
            snackText.setTextColor(Ui.TEXT);
            snackText.setTextSize(14);
            snackText.setMaxLines(2);
            snackText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sb.addView(snackText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            snackAction = new Button(this, null, android.R.attr.borderlessButtonStyle);
            snackAction.setTextColor(Ui.accentText(this));
            snackAction.setAllCaps(false);
            sb.addView(snackAction, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
            Button close = new Button(this, null, android.R.attr.borderlessButtonStyle);
            close.setText("✕");
            close.setTextColor(Ui.TEXT_2);
            close.setContentDescription("关闭");
            close.setOnClickListener(v -> hideSnack());
            sb.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
            sb.setVisibility(View.GONE);
            final FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.gravity = android.view.Gravity.BOTTOM;
            sp.setMargins(dp(12), 0, dp(12), dp(64));
            overlay.addView(sb, sp);
            // keyboard up (371): the overlay is already lifted above it (it fits the system windows),
            // and YouTube's bottom bar is under the keyboard, so the bar sits just above the keyboard
            overlay.setOnApplyWindowInsetsListener((v, ins) -> {
                try {
                    int ime = Build.VERSION.SDK_INT >= 30 ? ins.getInsets(android.view.WindowInsets.Type.ime()).bottom : 0;
                    int want = ime > 0 ? dp(12) : dp(64);
                    if (sp.bottomMargin != want) { sp.bottomMargin = want; sb.setLayoutParams(sp); }
                } catch (Throwable ignored) {}
                return v.onApplyWindowInsets(ins);
            });
            snack = sb;
            overlayRoot = overlay;

            addContentView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable t) {
            topBar = null;
            errorPanel = null;
            Diag.log("overlay", "setup failed: " + t);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showTopBar(int p) {
        if (topBar == null) return;
        boolean was = topBar.getVisibility() == View.VISIBLE && topBar.getAlpha() > 0.99f;
        topBar.animate().cancel();
        topBar.setAlpha(1f);
        topBar.setProgress(Math.max(p, was ? topBar.getProgress() : 0));
        topBar.setVisibility(View.VISIBLE);
    }

    /** Fills to the end, then fades out in 150 ms (364). */
    private void hideTopBar() {
        if (topBar == null || topBar.getVisibility() != View.VISIBLE) return;
        topBar.animate().cancel();
        topBar.setProgress(100, Ui.anim(this, 1) > 0);
        topBar.animate().alpha(0f).setStartDelay(Ui.anim(this, 120)).setDuration(Ui.anim(this, 150)).withEndAction(() -> {
            if (topBar != null) { topBar.setVisibility(View.GONE); topBar.setAlpha(1f); }
        }).start();
    }

    /** From the chrome client: page load progress 0..100, animated towards the new value (364). */
    public void onProgress(int p) {
        if (topBar == null || topBar.getVisibility() != View.VISIBLE) return;
        if (p >= 100) hideTopBar();
        else if (p > topBar.getProgress()) topBar.setProgress(p, Ui.anim(this, 1) > 0);
    }

    /** From the WebView client: the main page could not load (offline, DNS, timeout). */
    public void onMainFrameError(String url, int code) {
        loadError = true;
        failedUrl = url != null ? url : currentUrl;
        Diag.log("load-error", "code=" + code);
        if (PlaybackService.isPlaying()) PlaybackService.setNetNote(true);   // the lock screen says why (250)
        hideTopBar();
        if (swipe != null) swipe.setRefreshing(false);
        if (errorPanel == null) return;
        errorText.setText(code == android.webkit.WebViewClient.ERROR_TIMEOUT
                ? "加载超时。网络恢复后会自动重试。"
                : "请检查网络连接。网络恢复后会自动重试。");
        errorPanel.setVisibility(View.VISIBLE);
        registerErrNet();
    }

    private void hideError() {
        if (errorPanel != null && errorPanel.getVisibility() == View.VISIBLE) errorPanel.setVisibility(View.GONE);
        unregisterErrNet();
    }

    /** Internet panel on Android 10+, the Wi-Fi / network settings before that (357). */
    private void openNetworkSettings() {
        try {
            startActivity(new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_WIRELESS_SETTINGS));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    private void retryLoad() {
        if (web == null) return;
        String u = failedUrl;
        if (u == null || !u.startsWith("https://")) u = HOME;
        hideError();
        showTopBar(5);
        web.loadUrl(u);
    }

    private void registerErrNet() {
        if (Build.VERSION.SDK_INT < 24 || errNetCallback != null) return;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.ConnectivityManager.NetworkCallback cb = new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network network) {
                    MAIN.postDelayed(() -> {
                        if (errorPanel != null && errorPanel.getVisibility() == View.VISIBLE) retryLoad();
                    }, 1200);
                }
            };
            cm.registerDefaultNetworkCallback(cb);
            errNetCallback = cb;
        } catch (Throwable ignored) {}
    }

    private void unregisterErrNet() {
        if (errNetCallback == null) return;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            cm.unregisterNetworkCallback((android.net.ConnectivityManager.NetworkCallback) errNetCallback);
        } catch (Throwable ignored) {}
        errNetCallback = null;
    }

    // ===================== memory pressure (52) =====================

    /**
     * Low memory: drop WebView's in-memory resource cache and the page script's own bookkeeping.
     * Never the disk cache, cookies or storage, and nothing the playing video needs.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // only real pressure (252): going to the background is not a reason to throw caches away
        boolean act = level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE;
        long now = System.currentTimeMillis();
        if (!act || web == null || now - lastTrim < 60000) return;
        lastTrim = now;
        try {
            // playing with the screen off: only the script's own small caches; the WebView's stay
            if (!PlaybackService.isPlaying()) web.clearCache(false);
            js("ytl.trim&&ytl.trim()");
            Diag.log("trim", "level=" + level + " playing=" + PlaybackService.isPlaying());
        } catch (Throwable ignored) {}
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ===================== pull to refresh =====================

    private static boolean isPlayerUrl(String url) {
        return url != null && (url.contains("/watch") || url.contains("/shorts/") || url.contains("/live/"));
    }

    private boolean canPullToRefresh() {
        return web != null && pageAtTop && web.getScrollY() == 0 && Logic.isYouTubeHost(currentUrl)   // never on sign-in pages (349)
                && !isPlayerUrl(currentUrl) && chrome != null && !chrome.isFullscreen();
    }

    /**
     * The pull gesture is only armed when the finger goes down while the page is already
     * at the very top; any gesture that starts mid-page scrolls the WebView untouched.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int act = ev.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            touchStartedAtTop = canPullToRefresh();
            PlaybackService.noteInput();   // (277)
            MAIN.removeCallbacks(frameIdle);
            setWebFrameRate(true);         // (275)
            showFullscreenTools();         // (336)
        } else if (act == MotionEvent.ACTION_MOVE) {
            sampleRefreshRate();           // (391)
        } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            MAIN.removeCallbacks(frameIdle);
            MAIN.postDelayed(frameIdle, 3000);   // a fling settles first
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent ev) {
        PlaybackService.noteInput();   // (277)
        return super.dispatchKeyEvent(ev);
    }

    // ---- moon button over fullscreen video (336): shows on a tap, fades after 3 s, never takes the player's touches ----
    private android.widget.ImageButton fsTools;
    private final Runnable hideFsTools = () -> {
        if (fsTools != null) fsTools.animate().alpha(0f).setDuration(Ui.anim(this, 300)).withEndAction(() -> {
            if (fsTools != null) fsTools.setVisibility(View.GONE);
        }).start();
    };

    public void onFullscreenChanged(boolean on) {
        MAIN.removeCallbacks(hideFsTools);
        if (!on) {
            if (fsTools != null) { try { decor().removeView(fsTools); } catch (Throwable ignored) {} fsTools = null; }
            MAIN.removeCallbacks(frameIdle);
            MAIN.postDelayed(frameIdle, 3000);
            return;
        }
        MAIN.removeCallbacks(frameIdle);
        setWebFrameRate(true);
        try {
            android.widget.ImageButton b = new android.widget.ImageButton(this);
            b.setImageResource(R.drawable.ic_moon_white);
            b.setBackground(Ui.round(Ui.OVERLAY_PILL, dp(20)));
            b.setContentDescription("播放工具与睡眠定时");
            b.setOnClickListener(v -> { MAIN.removeCallbacks(hideFsTools); openTools(); });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(40), dp(40), android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(12);
            b.setVisibility(View.GONE);
            decor().addView(b, lp);
            fsTools = b;
            raiseOverlays();
            showFullscreenTools();
        } catch (Throwable t) {
            Diag.log("fs-tools", String.valueOf(t));
        }
    }

    private void showFullscreenTools() {
        if (fsTools == null || chrome == null || !chrome.isFullscreen() || lockView != null || blackView != null) return;
        // keep clear of a centred punch-hole camera in portrait fullscreen (cutout area is drawn into)
        try {
            int top = dp(12);
            android.view.WindowInsets wi = getWindow().getDecorView().getRootWindowInsets();
            if (wi != null && wi.getDisplayCutout() != null) top = Math.max(top, wi.getDisplayCutout().getSafeInsetTop() + dp(8));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fsTools.getLayoutParams();
            if (lp.topMargin != top) { lp.topMargin = top; fsTools.setLayoutParams(lp); }
        } catch (Throwable ignored) {}
        fsTools.animate().cancel();
        fsTools.setVisibility(View.VISIBLE);
        fsTools.setAlpha(1f);
        fsTools.bringToFront();
        MAIN.removeCallbacks(hideFsTools);
        MAIN.postDelayed(hideFsTools, 3000);
    }

    private void setupSwipe() {
        swipe.setColorSchemeColors(Ui.accent(this));
        swipe.setProgressBackgroundColorSchemeColor(Ui.SHEET);
        // pull feel (121): trigger a little earlier, spinner travels a bit further, settles 64dp down
        float dens = getResources().getDisplayMetrics().density;
        swipe.setDistanceToTriggerSync(Math.round(100 * dens));
        swipe.setSlingshotDistance(Math.round(140 * dens));
        swipe.setProgressViewEndTarget(false, Math.round(64 * dens));
        swipe.setOnChildScrollUpCallback((parent, child) -> !(touchStartedAtTop && canPullToRefresh()));
        swipe.setOnRefreshListener(this::refreshFeed);
    }

    // ===================== back navigation =====================

    private void setupBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    private void handleBack() {
        if (lockView != null) {               // touch lock: back does nothing (131)
            Toast.makeText(this, "触控已锁定，长按屏幕 1.5 秒解锁", Toast.LENGTH_SHORT).show();   // the bar would sit under the lock
        } else if (blackView != null) {
            stopBlackScreen();
        } else if (chrome != null && chrome.isFullscreen()) {
            chrome.exitFullscreen();
        } else if (web != null && web.canGoBack()) {
            web.goBack();
        } else if (PlaybackService.isPlaying()) {
            // playing: keep playing in the background instead of killing the WebView (118)
            moveTaskToBack(true);
        } else {
            long now = System.currentTimeMillis();
            if (now - lastBackAt < 2000) {
                clearLastVideo();
                finish();
            } else {
                lastBackAt = now;
                say("再按一次退出");
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    // ===================== intents =====================

    private static String urlFromIntent(Intent intent) {
        if (intent == null) return HOME;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            return normalize(intent.getData().toString());
        }
        if (Intent.ACTION_SEND.equals(action)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("https?://(?:[\\w-]+\\.)?(?:youtube\\.com|youtu\\.be)/\\S+").matcher(text);
                if (m.find()) return normalize(m.group());
            }
        }
        return HOME;
    }

    /** Shared links carry tracking parameters (si=, feature=, pp=, utm_*): drop them (68). */
    static String stripTracking(String url) {
        try {
            Uri u = Uri.parse(url);
            if (u.getQuery() == null) return url;
            Uri.Builder b = u.buildUpon().clearQuery();
            boolean changed = false;
            for (String k : u.getQueryParameterNames()) {
                String lk = k.toLowerCase(java.util.Locale.ROOT);
                if (lk.equals("si") || lk.equals("feature") || lk.equals("pp") || lk.startsWith("utm_")
                        || lk.equals("fbclid") || lk.equals("gclid")) { changed = true; continue; }
                for (String v : u.getQueryParameters(k)) b.appendQueryParameter(k, v);
            }
            return changed ? b.build().toString() : url;
        } catch (Exception e) {
            return url;
        }
    }

    private static String normalize(String url) {
        url = stripTracking(url);
        Uri u = Uri.parse(url);
        String unshort = YTProWebViewClient.unshort(u);
        if (unshort != null) return unshort;
        String host = u.getHost() == null ? "" : u.getHost();
        if (host.endsWith("youtu.be") && u.getPath() != null && u.getPath().length() > 1) {
            String t = u.getQueryParameter("t");
            return HOME + "watch?v=" + u.getPath().substring(1) + (t != null ? "&t=" + t : "");
        }
        return url;
    }

    // ===================== first-run permissions =====================

    private void askPermissionsOnce() {
        if (isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !Prefs.get(this).getBoolean(Prefs.ASKED_NOTIF, false)) {
            Prefs.put(this, Prefs.ASKED_NOTIF, true);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        askBatteryOnce();
    }

    private void askBatteryOnce() {
        if (Build.VERSION.SDK_INT < 23 || isIgnoringBattery() || Prefs.get(this).getBoolean(Prefs.ASKED_BATTERY, false)) return;
        Prefs.put(this, Prefs.ASKED_BATTERY, true);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("允许后台持续播放")
                .setMessage("为了息屏后也能一直播放（听着入睡），请在下一步允许 " + getString(R.string.app_name) + "「不受电池优化限制」。\n\n"
                        + "国产系统（ColorOS、MIUI/HyperOS、EMUI/HarmonyOS 等）还需要开启「自启动」并把耗电策略设为「无限制」，可点「自启动」直接跳过去。")
                .setPositiveButton("去设置", (d, w) -> requestIgnoreBattery())
                .setNeutralButton("自启动", (d, w) -> openAutostartSettings())
                .setNegativeButton("以后再说", null)
                .show();
    }

    public boolean isIgnoringBattery() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    @SuppressLint("BatteryLife")
    public void requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                openAppDetails();
            }
        }
    }

    public void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            askBatteryOnce();
        } else if (requestCode == REQ_MIC) {
            // the voice search that asked goes on by itself (411)
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (chrome != null) chrome.onMicPermission(ok);
        }
    }

    // ---- backup to / from a file (402): system file picker, no storage permission ----
    public static final int REQ_BACKUP_SAVE = 201;
    public static final int REQ_BACKUP_OPEN = 202;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_BACKUP_SAVE && requestCode != REQ_BACKUP_OPEN) return;
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (uri == null) return;
        if (requestCode == REQ_BACKUP_SAVE) SettingsPage.onBackupSaved(this, uri);
        else SettingsPage.onBackupOpened(this, uri);
    }
}
