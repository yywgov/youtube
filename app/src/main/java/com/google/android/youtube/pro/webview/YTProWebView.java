package com.google.android.youtube.pro.webview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.WebView;

/**
 * WebView that never tells Chromium it became hidden, so the page (and the
 * video in it) keeps running when the app is backgrounded or the screen is off.
 * Same idea as YTPro's background play, applied to both visibility hooks.
 */
public class YTProWebView extends WebView {

    public YTProWebView(Context context) { super(context); }

    public YTProWebView(Context context, AttributeSet attrs) { super(context, attrs); }

    public YTProWebView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        if (visibility == View.VISIBLE) super.onWindowVisibilityChanged(visibility);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        if (visibility == View.VISIBLE) super.onVisibilityChanged(changedView, visibility);
    }
}
