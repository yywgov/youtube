package com.google.android.youtube.pro.webview;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.android.youtube.pro.MainActivity;

import java.util.regex.Pattern;

public class YTProWebViewClient extends WebViewClient {

    /** Hosts that stay inside the app (YouTube itself + Google sign-in). */
    private static final Pattern IN_APP = Pattern.compile(
            "(^|\\.)(youtube\\.com|youtu\\.be|youtube-nocookie\\.com|gstatic\\.com|googleusercontent\\.com|ggpht\\.com|ytimg\\.com|googleapis\\.com)$"
                    + "|(^|\\.)google\\.[a-z]{2,3}(\\.[a-z]{2})?$");

    private final MainActivity activity;

    public YTProWebViewClient(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (AdBlocker.shouldBlock(request.getUrl())) return AdBlocker.empty(request);
        if (WatchPageFilter.applies(request)) return WatchPageFilter.fetch(request);
        return null;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return handleUrl(view, request.getUrl());
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return handleUrl(view, Uri.parse(url));
    }

    /** youtube.com/shorts/ID -> /watch?v=ID, channel /shorts tab -> /videos; null if not Shorts. */
    public static String unshort(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (!host.endsWith("youtube.com") || uri.getPath() == null) return null;
        java.util.regex.Matcher m = SHORTS.matcher(uri.getPath());
        if (m.find()) return "https://m.youtube.com/watch?v=" + m.group(1);
        m = CHANNEL_SHORTS.matcher(uri.getPath());
        if (m.matches()) return "https://m.youtube.com" + m.group(1) + "/videos";
        return null;
    }

    private static final Pattern SHORTS = Pattern.compile("^/shorts/([A-Za-z0-9_-]{6,})");
    private static final Pattern CHANNEL_SHORTS = Pattern.compile("^(/(?:@[^/]+|channel/[^/]+|c/[^/]+|user/[^/]+))/shorts/?$");

    private boolean handleUrl(WebView view, Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        if (!scheme.equals("http") && !scheme.equals("https")) {
            // intent://, vnd.youtube:, market:// ... ("open in app" banners) – ignore
            return true;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        // youtube.com/redirect?q=<external>  -> open the real target in a browser
        if (host.endsWith("youtube.com") && "/redirect".equals(uri.getPath())) {
            String q = uri.getQueryParameter("q");
            if (q != null) activity.openExternal(q);
            return true;
        }
        String watch = unshort(uri);
        if (watch != null) {
            view.loadUrl(watch);
            return true;
        }
        if (IN_APP.matcher(host).find()) return false;
        activity.openExternal(uri.toString());
        return true;
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        activity.onPageStarted(url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        activity.onPageFinished(url);
    }

    /** First paint of a new page (253): the refresh's no-cache mode ends here. */
    @Override
    public void onPageCommitVisible(WebView view, String url) {
        super.onPageCommitVisible(view, url);
        activity.onPageCommitVisible();
    }

    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);
        activity.onUrlChanged(url);
    }

    /** Main page could not load (offline, DNS, timeout): show the native retry screen. */
    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
        super.onReceivedError(view, request, error);
        if (request == null || !request.isForMainFrame() || error == null) return;
        int code = error.getErrorCode();
        if (code == ERROR_HOST_LOOKUP || code == ERROR_CONNECT || code == ERROR_TIMEOUT || code == ERROR_IO) {
            activity.onMainFrameError(request.getUrl() == null ? null : request.getUrl().toString(), code);
        }
    }

    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        // Renderer crashed or was killed for memory: rebuild instead of crashing the app.
        com.google.android.youtube.pro.Diag.log("renderer", "gone, crash=" + (detail != null && detail.didCrash()));
        activity.onRendererGone();
        return true;
    }
}
