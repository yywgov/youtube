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
        current = new WeakReference<>(this);
        setContentView(R.layout.main);
        swipe = findViewById(R.id.swipe);
        web = findViewById(R.id.web);
        script = readAsset("ytlite.js");
        requestHighRefreshRate();
        setupWebView();
        setupSwipe();
        setupBack();
        web.loadUrl(urlFromIntent(getIntent()));
        MAIN.postDelayed(this::askPermissionsOnce, 1500);
        Updater.cleanup(this);
        MAIN.postDelayed(() -> { if (!isFinishing()) Updater.autoCheck(this); }, 4000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestHighRefreshRate();
        Updater.onResume(this);
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
        s.setAllowFileAccess(false);
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
    }

    public void onPageFinished(String url) {
        currentUrl = url;
        injectFallback();
        swipe.setRefreshing(false);
        if (noCacheLoad) restoreCacheMode();
        if (clearHistoryOnFinish && web != null) {
            clearHistoryOnFinish = false;
            web.clearHistory(); // refreshed home is the new root: back leaves the app
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
        swipe.setRefreshing(true);
        web.evaluateJavascript("(window.ytl&&ytl.refresh)?(ytl.refresh(),1):0", r -> {
            if (!"1".equals(r)) hardReload();
        });
        MAIN.postDelayed(() -> { if (swipe != null) swipe.setRefreshing(false); }, 10000);
    }

    /** Reload the current page (home by default) bypassing the HTTP cache, with a cache-busting param. */
    public void hardReload() {
        if (web == null) return;
        swipe.setRefreshing(true);
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
        new SettingsDialog(this).show();
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
        swipe.setColorSchemeColors(0xFFFF0033);
        swipe.setProgressBackgroundColorSchemeColor(0xFF212121);
        swipe.setDistanceToTriggerSync(Math.round(120 * getResources().getDisplayMetrics().density));
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
        if (chrome != null && chrome.isFullscreen()) {
            chrome.exitFullscreen();
        } else if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            // keep playing in the background instead of killing the WebView
            moveTaskToBack(true);
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

    private static String normalize(String url) {
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
                .setMessage("为了熄屏后也能一直播放（听着入睡），请在下一步允许 YT Lite「不受电池优化限制」。\n\n"
                        + "国产系统（MIUI/HyperOS、ColorOS、EMUI/HarmonyOS 等）还需要在应用详情里开启「自启动」并把耗电策略设为「无限制」。")
                .setPositiveButton("去设置", (d, w) -> requestIgnoreBattery())
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
