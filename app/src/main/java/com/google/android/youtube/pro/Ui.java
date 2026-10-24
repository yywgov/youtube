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
    // (350) every native colour comes from here; a contract test keeps literals out of other classes
    public static final int CHIP = 0xFF333333;        // chip / pill buttons on a sheet
    public static final int SNACK = 0xF2323232;       // snack bar background
    public static final int HANDLE = 0xFF5F5F5F;      // drag handle on sheets
    public static final int HINT = 0xFF777777;        // input hint text
    public static final int TEXT_3 = 0xFFCCCCCC;      // secondary text on dialogs
    public static final int BLACK_HINT = 0xFF9E9E9E;  // text on the black screen, ≈7:1 on black (377)
    public static final int OVERLAY_PILL = 0xB0000000; // translucent pill (touch lock, fullscreen moon)
    public static final int PROGRESS = 0xFFFF0033;    // top load bar (YouTube red)
    public static final int SWITCH_OFF = 0xFFBDBDBD;
    public static final int BLACK = 0xFF000000;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int TRACK_OFF = 0x40FFFFFF;

    /** Same colour with another alpha (switch track). */
    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
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

    /** Native motion length (367): 0 when the system's "remove animations" is on. */
    public static int anim(Context c, int ms) {
        return Prefs.animationsOff(c) ? 0 : ms;
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
        if (android.os.Build.VERSION.SDK_INT >= 29) scroll.setForceDarkAllowed(false);   // (392)
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(a, 10), 0, dp(a, 12));
        View handle = new View(a);
        handle.setBackground(round(HANDLE, dp(a, 2)));
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
            if (!Prefs.animationsOff(a)) w.setWindowAnimations(android.R.style.Animation_InputMethod);   // (367)
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.4f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        return d;
    }

    /** Plain sheet row: title + optional one-line explanation, ≥ 48 dp touch target. */
    public static View sheetRow(Context c, String title, String sub, View.OnClickListener click) {
        return sheetRow(c, 0, title, sub, click);
    }

    /** Sheet row with a line icon on the left (362); icon 0 = none. */
    public static View sheetRow(Context c, int icon, String title, String sub, View.OnClickListener click) {
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(c, title, 16, TEXT));
        if (sub != null && !sub.isEmpty()) texts.addView(text(c, sub, 12, TEXT_2));
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10));
        l.setMinimumHeight(dp(c, 52));
        l.setGravity(Gravity.CENTER_VERTICAL);
        if (icon != 0) {
            android.widget.ImageView iv = new android.widget.ImageView(c);
            iv.setImageResource(icon);
            iv.setImageTintList(ColorStateList.valueOf(TEXT_2));
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24));
            ip.rightMargin = dp(c, 16);
            l.addView(iv, ip);
        }
        l.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (click != null) {
            ripple(l);
            l.setOnClickListener(click);
        }
        l.setContentDescription(sub == null ? title : title + "，" + sub);
        return l;
    }
}
