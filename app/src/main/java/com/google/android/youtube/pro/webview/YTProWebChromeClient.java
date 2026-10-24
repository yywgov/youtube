package com.google.android.youtube.pro.webview;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.widget.FrameLayout;

import com.google.android.youtube.pro.MainActivity;

/** Fullscreen video + mic permission for voice search. */
public class YTProWebChromeClient extends WebChromeClient {
    private final MainActivity activity;
    private View customView;
    private CustomViewCallback customViewCallback;
    private int savedUiVisibility;

    public YTProWebChromeClient(MainActivity activity) {
        this.activity = activity;
    }

    public boolean isFullscreen() {
        return customView != null;
    }

    @Override
    public Bitmap getDefaultVideoPoster() {
        // transparent instead of the grey play-button placeholder
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onShowCustomView(View view, CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customViewCallback = callback;
        android.view.Window w = activity.getWindow();
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams p = w.getAttributes();
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(p);
        }
        savedUiVisibility = w.getDecorView().getSystemUiVisibility();
        ((FrameLayout) w.getDecorView()).addView(view, new FrameLayout.LayoutParams(-1, -1));
        hideBars(w);
        activity.keepScreenOn("fullscreen", true);   // shared with the black screen (337)
        int lock = activity.orientationLock();
        activity.setRequestedOrientation(lock != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED ? lock
                : activity.portraitVideo ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        activity.raiseOverlays();
        activity.onFullscreenChanged(true);   // moon button over the video (336)
    }

    /**
     * System bars away for fullscreen (386): WindowInsetsController on Android 11+ (edge-to-edge on
     * 15/16 no longer flashes the bars or leaves the layout shifted), the old flags before that.
     */
    @SuppressWarnings("deprecation")
    private void hideBars(android.view.Window w) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.view.WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    c.hide(android.view.WindowInsets.Type.systemBars());
                    return;
                }
            } catch (Throwable ignored) {}
        }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @SuppressWarnings("deprecation")
    private void showBars(android.view.Window w) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.view.WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.show(android.view.WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_DEFAULT);
                }
            } catch (Throwable ignored) {}
        }
        w.getDecorView().setSystemUiVisibility(savedUiVisibility);
    }

    @Override
    public void onHideCustomView() {
        hide();
    }

    /** Leave fullscreen (back button). */
    public void exitFullscreen() {
        CustomViewCallback cb = customViewCallback;
        hide();
        if (cb != null) cb.onCustomViewHidden();
    }

    @SuppressWarnings("deprecation")
    private void hide() {
        if (customView == null) return;
        android.view.Window w = activity.getWindow();
        ((FrameLayout) w.getDecorView()).removeView(customView);
        customView = null;
        customViewCallback = null;
        showBars(w);
        activity.keepScreenOn("fullscreen", false);
        activity.onFullscreenChanged(false);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams p = w.getAttributes();
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            w.setAttributes(p);
        }
        activity.setRequestedOrientation(activity.orientationLock());
    }

    @Override
    public void onProgressChanged(android.webkit.WebView view, int newProgress) {
        activity.onProgress(newProgress);
    }

    /** No location for YouTube: deny quietly, never prompt. */
    @Override
    public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback callback) {
        if (callback != null) callback.invoke(origin, false, false);
    }

    /** Voice search waiting for the system microphone permission (411). */
    private PermissionRequest pendingMic;

    /**
     * Microphone for voice search (411): only a YouTube page by its host, only audio capture (never
     * whatever else the page asks for), and once the system permission is given the same request
     * goes on, without a second tap.
     */
    @Override
    public void onPermissionRequest(final PermissionRequest request) {
        String origin = request.getOrigin() != null ? request.getOrigin().toString() : "";
        boolean wantsMic = false;
        for (String r : request.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
        if (!com.google.android.youtube.pro.Logic.micOriginOk(origin) || !wantsMic) {
            request.deny();
            return;
        }
        final String[] audio = {PermissionRequest.RESOURCE_AUDIO_CAPTURE};
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (pendingMic != null) { try { pendingMic.deny(); } catch (Exception ignored) {} }
            pendingMic = request;
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MainActivity.REQ_MIC);
        } else {
            request.grant(audio);
        }
    }

    @Override
    public void onPermissionRequestCanceled(PermissionRequest request) {
        if (request == pendingMic) pendingMic = null;
    }

    public void onMicPermission(boolean granted) {
        PermissionRequest r = pendingMic;
        pendingMic = null;
        if (r == null) return;
        try {
            if (granted) r.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else r.deny();
        } catch (Exception ignored) {}
    }
}
