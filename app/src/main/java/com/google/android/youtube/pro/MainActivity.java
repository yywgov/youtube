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

    /** Run a snippet against the injected script (window.ytl) on the main thread. */
    public static void js(final String code) {
        Runnable r = () -> {
            MainActivity a = current.get();
            if (a == null || a.web == null) return;
            a.web.evaluateJavascript("window.ytl&&(function(){try{" + code + "}catch(e){}})()", null);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else MAIN.post(r);
    }

    // ===================== lifecycle =====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diag.init(this);
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
        String startUrl = urlFromIntent(getIntent());
        coldStart = savedInstanceState == null && HOME.equals(startUrl);
        // start page (63): subscriptions instead of home when launched from the icon
        if (coldStart && "subs".equals(Prefs.str(this, Prefs.START_PAGE, "home"))) startUrl = HOME + "feed/subscriptions";
        web.loadUrl(startUrl);
        if (coldStart) MAIN.postDelayed(this::offerResume, 3000);
        if (ACTION_SLEEP_MODE.equals(getIntent().getAction())) MAIN.postDelayed(this::startSleepMode, 1500);
        registerPowerSave();
        MAIN.postDelayed(this::maybeWhatsNew, 2500);
        MAIN.postDelayed(() -> { if (!isFinishing()) RemoteRules.autoCheck(this); }, 6000);
        MAIN.postDelayed(this::askPermissionsOnce, 1500);
        Updater.cleanup(this);
        MAIN.postDelayed(() -> { if (!isFinishing()) Updater.autoCheck(this); }, 4000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestHighRefreshRate();
        Updater.onResume(this);
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
        if (Build.VERSION.SDK_INT < 23) return;
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
                if (web != null) web.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_SLEEP_MODE.equals(intent.getAction())) { startSleepMode(); return; }
        String url = urlFromIntent(intent);
        boolean external = Intent.ACTION_VIEW.equals(intent.getAction()) || Intent.ACTION_SEND.equals(intent.getAction());
        if (external && web != null) web.loadUrl(url);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        js("ytl.bg(false)");
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        js("ytl.bg(true)");
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (current.get() == this) {
            SleepTimer.cancel(null);
            PlaybackService.stop(this);
            current = new WeakReference<>(null);
        }
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

        web.setBackgroundColor(0xFF0F0F0F);
        if (Build.VERSION.SDK_INT >= 35) web.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        if (Build.VERSION.SDK_INT >= 26) {
            // keep the renderer important even when not visible (screen off)
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }

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

    public void onRendererGone() {
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
        pageAtTop = true;
        loadError = false;
        showTopBar(10);
    }

    public void onPageFinished(String url) {
        currentUrl = url;
        injectFallback();
        swipe.setRefreshing(false);
        hideTopBar();
        if (!loadError) hideError();
        if (noCacheLoad) restoreCacheMode();
        if (clearHistoryOnFinish && web != null) {
            clearHistoryOnFinish = false;
            web.clearHistory(); // refreshed home is the new root: back leaves the app
            haptic();           // refresh finished (120)
        }
    }

    public void onUrlChanged(String url) {
        currentUrl = url;
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
        // the pull gesture's spinner goes away at once; the thin top bar shows the reload
        swipe.setRefreshing(false);
        showTopBar(5);
        web.evaluateJavascript("(window.ytl&&ytl.refresh)?(ytl.refresh(),1):0", r -> {
            if (!"1".equals(r)) hardReload();
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

    private void restoreCacheMode() {
        noCacheLoad = false;
        if (web != null) web.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
    }

    public void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
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
            int c = light ? 0xFFFFFFFF : (Prefs.on(this, Prefs.AMOLED) ? 0xFF000000 : android.graphics.Color.parseColor(pageTheme));
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
            z = Math.max(85, Math.min(130, Math.round(fs * 100)));
        }
        if (web.getSettings().getTextZoom() != z) web.getSettings().setTextZoom(z);
    }

    /** Light tap for key actions (120). */
    public void haptic() {
        try {
            if (!Prefs.on(this, Prefs.HAPTIC) || web == null) return;
            web.performHapticFeedback(Build.VERSION.SDK_INT >= 23 ? android.view.HapticFeedbackConstants.CONTEXT_CLICK
                    : android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        } catch (Throwable ignored) {}
    }

    public static void hapticNow() {
        MAIN.post(() -> { MainActivity a = current.get(); if (a != null) a.haptic(); });
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
        final String id, title, channel;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            id = o.optString("id", "");
            title = o.optString("title", "");
            channel = o.optString("channel", "");
        } catch (Exception e) {
            return;
        }
        if (!id.matches("[A-Za-z0-9_-]{6,20}") || isFinishing()) return;
        haptic();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView head = Ui.text(this, title.isEmpty() ? "视频" : title, 14, Ui.TEXT_2);
        head.setMaxLines(2);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.setPadding(dp(20), dp(4), dp(20), dp(8));
        box.addView(head);
        final android.app.Dialog[] d = new android.app.Dialog[1];
        box.addView(Ui.sheetRow(this, "稍后观看", "加入 YouTube 的「稍后观看」（需登录）", v -> { d[0].dismiss(); js("ytl.watchLater(" + org.json.JSONObject.quote(id) + ")"); }));
        box.addView(Ui.sheetRow(this, "不感兴趣 / 更多", "打开 YouTube 自己的菜单", v -> { d[0].dismiss(); js("ytl.openCardMenu()"); }));
        if (!channel.isEmpty()) {
            box.addView(Ui.sheetRow(this, "不再显示此频道", channel + " · 只在本机屏蔽，可在设置里撤销", v -> {
                d[0].dismiss();
                Prefs.addLine(this, Prefs.BLOCK_CHANNELS, channel);
                applySettings();
                Toast.makeText(this, "已屏蔽：" + channel, Toast.LENGTH_SHORT).show();
            }));
        }
        box.addView(Ui.sheetRow(this, "本地隐藏这个视频", "以后在列表里不再出现", v -> { d[0].dismiss(); js("ytl.hideVideo(" + org.json.JSONObject.quote(id) + ")"); }));
        box.addView(Ui.sheetRow(this, "复制链接", "https://youtu.be/" + id, v -> {
            d[0].dismiss();
            try {
                ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(android.content.ClipData.newPlainText("YouTube", "https://youtu.be/" + id));
                Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        }));
        d[0] = Ui.sheet(this, box);
        d[0].show();
    }

    // ===================== one-tap sleep mode (97) =====================

    public static boolean sleepModeOn() { return sleepModeOn; }

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
    }

    private void stopSleepMode() {
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
        if (web == null) { PlayerTools.show(this, null); return; }
        web.evaluateJavascript("(window.ytl&&ytl.state)?ytl.state():''", r -> {
            org.json.JSONObject st = null;
            try {
                if (r != null && r.length() > 2 && !"null".equals(r)) {
                    String json = new org.json.JSONTokener(r).nextValue().toString();
                    st = new org.json.JSONObject(json);
                }
            } catch (Exception ignored) {}
            if (!isFinishing()) PlayerTools.show(this, st);
        });
    }

    // ===================== snack bar: night sleep prompt (18), continue after a kill (27) =====================

    private void showSnack(String text, String action, final Runnable onAction, long ms) {
        if (snack == null) return;
        snackText.setText(text);
        if (action != null) {
            snackAction.setText(action);
            snackAction.setVisibility(View.VISIBLE);
            snackAction.setOnClickListener(v -> { hideSnack(); if (onAction != null) onAction.run(); });
        } else snackAction.setVisibility(View.GONE);
        snack.setVisibility(View.VISIBLE);
        snack.setAlpha(0f);
        snack.animate().alpha(1f).setDuration(200).start();
        MAIN.removeCallbacks(hideSnackRun);
        MAIN.postDelayed(hideSnackRun, ms);
    }

    private void hideSnack() {
        MAIN.removeCallbacks(hideSnackRun);
        if (snack != null) snack.setVisibility(View.GONE);
    }

    private boolean snackShowing() {
        return snack != null && snack.getVisibility() == View.VISIBLE;
    }

    private final Runnable nightPromptRun = this::maybeNightPrompt;

    /** 22:00-05:00, timer off, once per night: offer the usual sleep timer (18). */
    private void maybeNightPrompt() {
        try {
            if (isFinishing() || !visible || SleepTimer.active() || snackShowing() || blackView != null || lockView != null) return;
            if (!Prefs.on(this, Prefs.NIGHT_PROMPT)) return;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
            if (h < 22 && h >= 5) return;
            if (h < 5) cal.add(java.util.Calendar.DAY_OF_MONTH, -1);
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

    @SuppressWarnings("deprecation")
    public void startBlackScreen() {
        if (blackView != null) return;
        try {
            FrameLayout v = new FrameLayout(this);
            v.setBackgroundColor(0xFF000000);
            v.setClickable(true);
            v.setOnClickListener(x -> stopBlackScreen());
            TextView hint = new TextView(this);
            hint.setText("黑屏播放中 · 轻点恢复");
            hint.setTextColor(0xFF555555);
            hint.setTextSize(14);
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER);
            v.addView(hint, hp);
            hint.animate().alpha(0f).setStartDelay(2500).setDuration(800).start();
            decor().addView(v, new FrameLayout.LayoutParams(-1, -1));
            v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            savedBrightness = lp.screenBrightness;
            lp.screenBrightness = 0.01f;
            getWindow().setAttributes(lp);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            blackView = v;
        } catch (Throwable t) {
            Diag.log("black", String.valueOf(t));
        }
    }

    public void stopBlackScreen() {
        if (blackView == null) return;
        try {
            decor().removeView(blackView);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = savedBrightness;
            getWindow().setAttributes(lp);
            if (chrome == null || !chrome.isFullscreen()) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) {}
        blackView = null;
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
            v.setBackgroundColor(0x00000000);
            final TextView pill = new TextView(this);
            pill.setText("触控已锁定 · 长按 1.5 秒解锁");
            pill.setTextColor(0xFFFFFFFF);
            pill.setTextSize(13);
            pill.setPadding(dp(14), dp(8), dp(14), dp(8));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xB0000000);
            bg.setCornerRadius(dp(18));
            pill.setBackground(bg);
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            pp.topMargin = dp(48);
            v.addView(pill, pp);
            pill.animate().alpha(0.15f).setStartDelay(2500).setDuration(600).start();
            final Runnable unlock = this::stopTouchLock;
            v.setOnTouchListener((x, ev) -> {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pill.animate().cancel();
                        pill.setAlpha(1f);
                        pill.setText("继续按住解锁…");
                        MAIN.postDelayed(unlock, 1500);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        MAIN.removeCallbacks(unlock);
                        pill.setText("触控已锁定 · 长按 1.5 秒解锁");
                        pill.animate().alpha(0.15f).setStartDelay(1500).setDuration(600).start();
                        break;
                    default:
                        break;
                }
                return true; // swallow everything
            });
            decor().addView(v, new FrameLayout.LayoutParams(-1, -1));
            lockView = v;
        } catch (Throwable t) {
            Diag.log("lock", String.valueOf(t));
        }
    }

    public void stopTouchLock() {
        if (lockView == null) return;
        try { decor().removeView(lockView); } catch (Throwable ignored) {}
        lockView = null;
        haptic();
        Toast.makeText(this, "已解锁", Toast.LENGTH_SHORT).show();
    }

    /** Fullscreen video was added to the decor view: keep our overlays above it. */
    public void raiseOverlays() {
        if (lockView != null) lockView.bringToFront();
        if (blackView != null) blackView.bringToFront();
    }

    // ===================== orientation lock (132) =====================

    public int orientationLock() { return orientationLock; }

    public void setOrientationLock(int o) {
        orientationLock = o;
        if (chrome == null || !chrome.isFullscreen()) setRequestedOrientation(o);
        Toast.makeText(this, o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED ? "屏幕方向：跟随系统"
                : o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ? "已锁定竖屏" : "已锁定横屏", Toast.LENGTH_SHORT).show();
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
                topBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFF0033));
                topBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00000000));
            }
            topBar.setVisibility(View.GONE);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
            bp.gravity = android.view.Gravity.TOP;
            overlay.addView(topBar, bp);

            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setGravity(android.view.Gravity.CENTER);
            panel.setBackgroundColor(0xFF0F0F0F);
            panel.setClickable(true); // swallow touches while shown
            panel.setPadding(dp(32), dp(32), dp(32), dp(32));
            TextView title = new TextView(this);
            title.setText("无法连接到 YouTube");
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(18);
            title.setGravity(android.view.Gravity.CENTER);
            panel.addView(title);
            errorText = new TextView(this);
            errorText.setTextColor(0xFFAAAAAA);
            errorText.setTextSize(14);
            errorText.setGravity(android.view.Gravity.CENTER);
            errorText.setPadding(0, dp(8), 0, dp(20));
            panel.addView(errorText);
            Button retry = new Button(this);
            retry.setText("重试");
            retry.setOnClickListener(v -> retryLoad());
            panel.addView(retry, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            panel.setVisibility(View.GONE);
            errorPanel = panel;
            overlay.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout sb = new LinearLayout(this);
            sb.setOrientation(LinearLayout.HORIZONTAL);
            sb.setGravity(android.view.Gravity.CENTER_VERTICAL);
            sb.setPadding(dp(16), dp(6), dp(6), dp(6));
            android.graphics.drawable.GradientDrawable sbg = new android.graphics.drawable.GradientDrawable();
            sbg.setColor(0xF2323232);
            sbg.setCornerRadius(dp(10));
            sb.setBackground(sbg);
            sb.setElevation(dp(6));
            snackText = new TextView(this);
            snackText.setTextColor(0xFFFFFFFF);
            snackText.setTextSize(14);
            snackText.setMaxLines(2);
            snackText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sb.addView(snackText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            snackAction = new Button(this, null, android.R.attr.borderlessButtonStyle);
            snackAction.setTextColor(0xFF3EA6FF);
            snackAction.setAllCaps(false);
            sb.addView(snackAction, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
            Button close = new Button(this, null, android.R.attr.borderlessButtonStyle);
            close.setText("✕");
            close.setTextColor(0xFFAAAAAA);
            close.setContentDescription("关闭");
            close.setOnClickListener(v -> hideSnack());
            sb.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
            sb.setVisibility(View.GONE);
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.gravity = android.view.Gravity.BOTTOM;
            sp.setMargins(dp(12), 0, dp(12), dp(64));
            overlay.addView(sb, sp);
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
        topBar.setProgress(Math.max(p, topBar.getVisibility() == View.VISIBLE ? topBar.getProgress() : 0));
        topBar.setVisibility(View.VISIBLE);
    }

    private void hideTopBar() {
        if (topBar != null) topBar.setVisibility(View.GONE);
    }

    /** From the chrome client: page load progress 0..100. */
    public void onProgress(int p) {
        if (topBar == null || topBar.getVisibility() != View.VISIBLE) return;
        if (p >= 100) hideTopBar();
        else if (p > topBar.getProgress()) topBar.setProgress(p);
    }

    /** From the WebView client: the main page could not load (offline, DNS, timeout). */
    public void onMainFrameError(String url, int code) {
        loadError = true;
        failedUrl = url != null ? url : currentUrl;
        Diag.log("load-error", "code=" + code);
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
        boolean act = level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND;
        long now = System.currentTimeMillis();
        if (!act || web == null || now - lastTrim < 60000) return;
        lastTrim = now;
        try {
            web.clearCache(false);
            js("ytl.trim&&ytl.trim()");
            Diag.log("trim", "level=" + level);
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
        return web != null && pageAtTop && web.getScrollY() == 0
                && !isPlayerUrl(currentUrl) && chrome != null && !chrome.isFullscreen();
    }

    /**
     * The pull gesture is only armed when the finger goes down while the page is already
     * at the very top; any gesture that starts mid-page scrolls the WebView untouched.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) touchStartedAtTop = canPullToRefresh();
        return super.dispatchTouchEvent(ev);
    }

    private void setupSwipe() {
        swipe.setColorSchemeColors(Ui.accent(this));
        swipe.setProgressBackgroundColorSchemeColor(0xFF212121);
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
            Toast.makeText(this, "触控已锁定，长按屏幕 1.5 秒解锁", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
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
                .setMessage("为了熄屏后也能一直播放（听着入睡），请在下一步允许 " + getString(R.string.app_name) + "「不受电池优化限制」。\n\n"
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
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "麦克风已授权，请再点一次语音搜索", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
