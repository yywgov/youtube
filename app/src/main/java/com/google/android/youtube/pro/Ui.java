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
    /** Brand blue of the app icon (351); a lighter tint of it for text on dark surfaces (contrast ≥ 4.5:1). */
    public static final int DEFAULT_ACCENT = 0xFF2F6BFF;
    public static final int DEFAULT_ACCENT_TEXT = 0xFF6B95FF;
    public static final int OLD_DEFAULT_BLUE = 0xFF3EA6FF;   // the default before 1.8, offered as "天蓝"
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
    public static final int RING_TRACK = 0x33FFFFFF;      // unfilled part of the touch-lock ring (369)
    public static final int SWITCH_THUMB = 0xFFFFFFFF;
    public static final int SWITCH_THUMB_OFF = 0xFFE0E0E0;
    public static final int PRESSED = 0x22FFFFFF;
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

    /** Accent for text (351): the user's colour, or the lighter brand tint that reads well on dark grey. */
    public static int accentText(Context c) {
        int a = accent(c);
        return a == DEFAULT_ACCENT ? DEFAULT_ACCENT_TEXT : a;
    }

    // ---- haptics (372) ----
    public static final int HAPTIC_TAP = 0, HAPTIC_CONFIRM = 1, HAPTIC_REJECT = 2, HAPTIC_LONG = 3;

    /** HapticFeedbackConstants for a kind; confirm / reject only exist on Android 11+. */
    public static int hapticConstant(int kind) {
        if (kind == HAPTIC_LONG) return android.view.HapticFeedbackConstants.LONG_PRESS;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (kind == HAPTIC_CONFIRM) return android.view.HapticFeedbackConstants.CONFIRM;
            if (kind == HAPTIC_REJECT) return android.view.HapticFeedbackConstants.REJECT;
        }
        return android.view.HapticFeedbackConstants.CONTEXT_CLICK;
    }

    /** A named accessibility action (375) that runs {@code r}; also what a screen reader's click does. */
    public static void a11yAction(View v, final String label, final Runnable r) {
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, label));
            }
            @Override public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) { r.run(); return true; }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    /** Small progress ring (369): fills once over the given time, reset() empties it. */
    public static final class Ring extends View {
        private final android.graphics.Paint track = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint arc = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF box = new android.graphics.RectF();
        private float sweep = 0f;
        private android.animation.ValueAnimator anim;

        public Ring(Context c, int color) {
            super(c);
            float w = dp(c, 2.5f);
            track.setStyle(android.graphics.Paint.Style.STROKE);
            track.setStrokeWidth(w);
            track.setColor(RING_TRACK);
            arc.setStyle(android.graphics.Paint.Style.STROKE);
            arc.setStrokeWidth(w);
            arc.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            arc.setColor(color);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        public void run(long ms) {
            reset();
            anim = android.animation.ValueAnimator.ofFloat(0f, 360f);
            anim.setDuration(Math.max(1, ms));
            anim.setInterpolator(new android.view.animation.LinearInterpolator());
            anim.addUpdateListener(a -> { sweep = (Float) a.getAnimatedValue(); invalidate(); });
            anim.start();
        }

        public void reset() {
            if (anim != null) { anim.cancel(); anim = null; }
            sweep = 0f;
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float pad = track.getStrokeWidth();
            box.set(pad, pad, getWidth() - pad, getHeight() - pad);
            c.drawOval(box, track);
            if (sweep > 0) c.drawArc(box, -90f, sweep, false, arc);
        }
    }

    /** Filled accent button (357): the main action of a screen. */
    public static android.widget.Button filledButton(Context c, String label) {
        android.widget.Button b = new android.widget.Button(c, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setBackground(round(accent(c), dp(c, 24)));
        return b;
    }

    /** Text-only accent button (357): the secondary action next to a filled one. */
    public static android.widget.Button textButton(Context c, String label) {
        android.widget.Button b = new android.widget.Button(c, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(accentText(c));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        return b;
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
        TextView t = text(c, s, 13, accentText(c));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(c, 20), dp(c, 14), dp(c, 20), dp(c, 4));
        heading(t);
        return t;
    }

    /** Screen readers can jump from heading to heading (379). */
    public static void heading(View v) {
        if (android.os.Build.VERSION.SDK_INT >= 28) v.setAccessibilityHeading(true);
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
            w.setLayout(sheetWidth(a), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (!Prefs.animationsOff(a)) w.setWindowAnimations(android.R.style.Animation_InputMethod);   // (367)
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.4f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        return d;
    }

    /** Full width on a phone; on a screen wider than 600 dp at most 560 dp, centred (385). */
    public static int sheetWidth(Context c) {
        int wdp = c.getResources().getConfiguration().screenWidthDp;
        return wdp > 600 ? dp(c, 560) : ViewGroup.LayoutParams.MATCH_PARENT;
    }

    /** Widest the settings content gets (385): 720 dp, centred on tablets / landscape. */
    public static int pageMaxWidth(Context c) {
        int wdp = c.getResources().getConfiguration().screenWidthDp;
        return wdp > 760 ? dp(c, 720) : ViewGroup.LayoutParams.MATCH_PARENT;
    }

    /** Section header inside a sheet (352): accent, bold, heading for screen readers. */
    public static TextView header(Context c, String label) {
        TextView t = text(c, label, 13, accentText(c));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 2));
        heading(t);
        return t;
    }

    /**
     * Pill button on a sheet (352/376): at least 48 dp high but grows with large text, the label may
     * take two lines instead of being cut off.
     */
    public static android.widget.Button chip(Context c, String label, View.OnClickListener click) {
        android.widget.Button b = new android.widget.Button(c, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackground(round(CHIP, dp(c, 20)));
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setMaxLines(2);
        b.setPadding(dp(c, 8), dp(c, 6), dp(c, 8), dp(c, 6));
        b.setOnClickListener(click);
        return b;
    }

    /** Layout params for a chip in a row: equal width, height follows the text (376). */
    public static LinearLayout.LayoutParams chipWeight(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(c, 4), dp(c, 2), dp(c, 4), dp(c, 2));
        return lp;
    }

    /** A chip that is the current choice shows a check icon instead of a ✓ character (354). */
    public static void markChip(android.widget.Button b, boolean on) {
        if (!on) { b.setCompoundDrawablesRelative(null, null, null, null); return; }
        try {
            android.graphics.drawable.Drawable d = b.getContext().getDrawable(R.drawable.ic_check);
            if (d == null) return;
            d = d.mutate();
            d.setTint(accentText(b.getContext()));
            int sz = dp(b.getContext(), 18);
            d.setBounds(0, 0, sz, sz);
            b.setCompoundDrawablesRelative(d, null, null, null);
            b.setCompoundDrawablePadding(dp(b.getContext(), 4));
        } catch (Throwable ignored) {}
    }

    public interface Pick { void on(int which); }

    /**
     * Single choice as a bottom sheet (353) instead of a system dialog: one row per option, the
     * current one with a check icon (354); optional colour dots in front (361).
     */
    public static Dialog choiceSheet(Activity a, String title, String[] labels, int[] swatches, int sel, final Pick cb) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView head = text(a, title, 18, TEXT);
        head.setPadding(dp(a, 20), dp(a, 4), dp(a, 20), dp(a, 8));
        heading(head);
        box.addView(head);
        final Dialog[] d = new Dialog[1];
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            LinearLayout l = new LinearLayout(a);
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setPadding(dp(a, 20), dp(a, 8), dp(a, 16), dp(a, 8));
            l.setMinimumHeight(dp(a, 52));
            if (swatches != null && i < swatches.length) {
                View dot = new View(a);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(swatches[i]);
                dot.setBackground(g);
                LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20));
                dp.rightMargin = dp(a, 16);
                l.addView(dot, dp);
            }
            TextView t = text(a, labels[i], 16, TEXT);
            l.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (i == sel) {
                android.widget.ImageView ck = new android.widget.ImageView(a);
                ck.setImageResource(R.drawable.ic_check);
                ck.setImageTintList(ColorStateList.valueOf(accentText(a)));
                ck.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                l.addView(ck, new LinearLayout.LayoutParams(dp(a, 24), dp(a, 24)));
            }
            ripple(l);
            l.setContentDescription(labels[i]);
            if (android.os.Build.VERSION.SDK_INT >= 30) l.setStateDescription(i == sel ? "已选中" : "未选中");
            l.setOnClickListener(v -> { d[0].dismiss(); cb.on(which); });
            box.addView(l);
        }
        d[0] = sheet(a, box);
        d[0].show();
        return d[0];
    }

    /**
     * Clearer switch (355): a 52 × 32 dp rounded track in the accent colour when on, a raised grey
     * track when off, and a white knob; still the platform Switch underneath.
     */
    public static void styleSwitch(android.widget.Switch sw) {
        try {
            Context c = sw.getContext();
            int acc = accent(c);
            android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
            GradientDrawable on = round(acc, dp(c, 16));
            on.setSize(dp(c, 52), dp(c, 32));
            GradientDrawable off = round(SURFACE_HI, dp(c, 16));
            off.setStroke(dp(c, 2), TEXT_2);
            off.setSize(dp(c, 52), dp(c, 32));
            track.addState(new int[]{android.R.attr.state_checked}, on);
            track.addState(new int[]{}, off);
            android.graphics.drawable.StateListDrawable thumb = new android.graphics.drawable.StateListDrawable();
            thumb.addState(new int[]{android.R.attr.state_checked}, knob(c, SWITCH_THUMB, 6));
            thumb.addState(new int[]{}, knob(c, SWITCH_THUMB_OFF, 8));
            sw.setThumbTintList(null);
            sw.setTrackTintList(null);
            sw.setTrackDrawable(track);
            sw.setThumbDrawable(thumb);
            sw.setSwitchMinWidth(dp(c, 52));
            sw.setShowText(false);
        } catch (Throwable ignored) {}
    }

    private static android.graphics.drawable.Drawable knob(Context c, int color, int insetDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        int in = dp(c, insetDp), sz = dp(c, 32) - 2 * in;
        g.setSize(sz, sz);
        return new android.graphics.drawable.InsetDrawable(g, in);
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
