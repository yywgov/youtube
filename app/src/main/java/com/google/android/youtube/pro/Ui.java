package com.google.android.youtube.pro;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * One set of design tokens for every native screen the app draws (164):
 * colours, corner radius, spacing, motion (≈200 ms, 166), plus the bottom-sheet frame (165).
 */
public final class Ui {
    public static final int BG = 0xFF0F0F0F;
    public static final int SURFACE = 0xFF1E1E1E;
    public static final int SURFACE_HI = 0xFF2A2A2A;
    public static final int SHEET = 0xFF212121;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_2 = 0xFFAAAAAA;
    public static final int DIVIDER = 0x1FFFFFFF;
    public static final int DEFAULT_ACCENT = 0xFF3EA6FF;
    public static final int RADIUS_DP = 16;
    public static final int ROW_MIN_DP = 56;
    public static final int ANIM_MS = 200;

    private Ui() {}

    /** Accent colour chosen in settings (103), or the default blue. */
    public static int accent(Context c) {
        String a = Prefs.str(c, Prefs.ACCENT, "");
        try { if (a.matches("#[0-9a-fA-F]{6}")) return 0xFF000000 | Integer.parseInt(a.substring(1), 16); } catch (Exception ignored) {}
        return DEFAULT_ACCENT;
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    public static TextView title(Context c, CharSequence s) {
        TextView t = text(c, s, 13, accent(c));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(c, 20), dp(c, 14), dp(c, 20), dp(c, 4));
        return t;
    }

    public static void ripple(View v) {
        TypedValue tv = new TypedValue();
        v.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        v.setBackgroundResource(tv.resourceId);
    }

    public static ColorStateList accentList(Context c) {
        return ColorStateList.valueOf(accent(c));
    }

    /** Bottom sheet: rounded top, slides up, dim behind, content scrolls. */
    public static Dialog sheet(Activity a, View content) {
        ScrollView scroll = new ScrollView(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(a, 10), 0, dp(a, 12));
        View handle = new View(a);
        handle.setBackground(round(0xFF5F5F5F, dp(a, 2)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(a, 36), dp(a, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(a, 6);
        box.addView(handle, hp);
        box.addView(content);
        scroll.addView(box);
        Dialog d = new Dialog(a, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        d.setContentView(scroll);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(SHEET);
            float r = dp(a, RADIUS_DP);
            bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            w.setBackgroundDrawable(bg);
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.4f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        return d;
    }

    /** Plain sheet row: title + optional one-line explanation, ≥ 48 dp touch target. */
    public static View sheetRow(Context c, String title, String sub, View.OnClickListener click) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10));
        l.setMinimumHeight(dp(c, 52));
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.addView(text(c, title, 16, TEXT));
        if (sub != null && !sub.isEmpty()) l.addView(text(c, sub, 12, TEXT_2));
        if (click != null) {
            ripple(l);
            l.setOnClickListener(click);
        }
        l.setContentDescription(sub == null ? title : title + "，" + sub);
        return l;
    }
}
