package com.google.android.youtube.pro;

import android.content.Intent;
import android.net.Uri;

import com.google.android.youtube.pro.webview.YTProWebViewClient;

/** Links that open the app (moved out of MainActivity, 437; behaviour unchanged). */
final class Links {
    private Links() {}

    /** Page to open for a VIEW intent (a YouTube link) or a SEND intent (text with one); home otherwise. */
    static String fromIntent(Intent intent, String home) {
        if (intent == null) return home;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            return normalize(intent.getData().toString(), home);
        }
        if (Intent.ACTION_SEND.equals(action)) {
            String link = Logic.findYouTubeLink(intent.getStringExtra(Intent.EXTRA_TEXT));
            if (link != null) return normalize(link, home);
        }
        return home;
    }

    /** Shared links carry tracking parameters (si=, feature=, pp=, utm_*): drop them (68). */
    static String stripTracking(String url) {
        try {
            Uri u = Uri.parse(url);
            if (u.getQuery() == null) return url;
            Uri.Builder b = u.buildUpon().clearQuery();
            boolean changed = false;
            for (String k : u.getQueryParameterNames()) {
                if (Logic.isTrackingParam(k)) { changed = true; continue; }
                for (String v : u.getQueryParameters(k)) b.appendQueryParameter(k, v);
            }
            return changed ? b.build().toString() : url;
        } catch (Exception e) {
            return url;
        }
    }

    private static String normalize(String url, String home) {
        url = stripTracking(url);
        Uri u = Uri.parse(url);
        String unshort = YTProWebViewClient.unshort(u);
        if (unshort != null) return unshort;
        String host = u.getHost() == null ? "" : u.getHost();
        if (host.endsWith("youtu.be") && u.getPath() != null && u.getPath().length() > 1) {
            String t = u.getQueryParameter("t");
            return home + "watch?v=" + u.getPath().substring(1) + (t != null ? "&t=" + t : "");
        }
        return url;
    }
}
