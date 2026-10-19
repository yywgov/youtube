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
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        activity.setRequestedOrientation(activity.portraitVideo
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
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
        w.getDecorView().setSystemUiVisibility(savedUiVisibility);
        w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams p = w.getAttributes();
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            w.setAttributes(p);
        }
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @Override
    public void onPermissionRequest(final PermissionRequest request) {
        String origin = request.getOrigin() != null ? request.getOrigin().toString() : "";
        if (!origin.contains("youtube.com")) {
            request.deny();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MainActivity.REQ_MIC);
            request.deny();
        } else {
            request.grant(request.getResources());
        }
    }
}
