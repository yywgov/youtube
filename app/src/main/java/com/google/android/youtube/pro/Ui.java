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
import android.widget.FrameLayout;
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
    public static final int TEXT = 0xFFF1F1F1;           // text 1 (same as m.youtube's text colour)
    public static final int TEXT_2 = 0xFFAAAAAA;
    public static final int DIVIDER = 0x1FFFFFFF;
    /**
     * Brand red (v1.23, DESIGN.md): the lit face of the app icon. ACCENT fills (white text on it 5.9:1),
     * ACCENT_INK is the only red used for text, icons and dots on dark surfaces (6.7:1 on black).
     */
    public static final int DEFAULT_ACCENT = 0xFFC8102E;
    public static final int ACCENT_PRESSED = 0xFFA60D26;
    public static final int ACCENT_INK = 0xFFFF5468;
    public static final int ACCENT_TINT = 0x38C8102E;
    // (350) every native colour comes from here; a contract test keeps literals out of other classes
    public static final int CHIP = 0xFF333333;        // chip / pill buttons on a sheet
    public static final int SNACK = 0xFF2A2A2A;       // snack bar background (v1.26, same as the injected #ytl-sb)
    public static final int HANDLE = 0xFF5F5F5F;      // drag handle on sheets
    public static final int HINT = 0xFF777777;        // input hint text
    public static final int TEXT_3 = 0xFFCCCCCC;      // secondary text on dialogs
    public static final int BLACK_HINT = 0xFF9E9E9E;  // text on the black screen, ≈7:1 on black (377)
    public static final int OVERLAY_PILL = 0xB0000000; // translucent pill (touch lock, fullscreen moon)
    public static final int SWITCH_OFF = 0xFF8A8A8A;   // M3 off outline + knob
    public static final int BLACK = 0xFF000000;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int TRACK_OFF = 0x40FFFFFF;

    /** Settings cards on the pure-black theme (v1.15): just enough lift to keep the groups apart. */
    public static final int SURFACE_AMOLED = 0xFF121212;
    // three surface levels (v1.24, DESIGN.md): card / sheet / control, pure black and dark
    public static final int SURFACE_1_BLACK = 0xFF121212, SURFACE_2_BLACK = 0xFF1A1A1A, SURFACE_3_BLACK = 0xFF262626;
    public static final int SURFACE_1_DARK = 0xFF1C1C1C, SURFACE_2_DARK = 0xFF212121, SURFACE_3_DARK = 0xFF2E2E2E;
    public static final int TEXT_TERTIARY = 0xFF7C7C7C;   // version line, chevrons (4.8:1 on black)
    public static final int STATUS_NOTE = 0xFFE9B8BF;     // explanation line on the red status card
    public static final int ACCENT_LINE = 0x24FF5468;     // 14 % bright-red outline of the status card
    public static final int HAIRLINE = 0x14FFFFFF;        // 8 % white
    public static final int HAIRLINE_STRONG = 0x24FFFFFF; // 14 % white

    /** Pure-black (AMOLED) theme switched on (91; on by default since v1.15). */
    public static boolean amoled(Context c) { return Prefs.on(c, Prefs.AMOLED); }

    /** Native background: pure black with AMOLED, the page grey otherwise (settings, sheets, WebView, bars). */
    public static int bg(Context c) { return amoled(c) ? BLACK : BG; }

    /** Bottom-sheet / dialog background: surface 2. */
    public static int sheetBg(Context c) { return amoled(c) ? SURFACE_2_BLACK : SURFACE_2_DARK; }

    /** Settings card background: surface 1. */
    public static int surface(Context c) { return amoled(c) ? SURFACE_1_BLACK : SURFACE_1_DARK; }

    /** Chips, segmented controls, tonal buttons, the icon squares of the group rows: surface 3. */
    public static int chipBg(Context c) { return amoled(c) ? SURFACE_3_BLACK : SURFACE_3_DARK; }

    // ---- type scale (v1.24, DESIGN.md): only weights 400 and 500, size / line height in sp ----
    public static final class Type {
        public final float sp, lh; public final boolean medium;
        Type(float sp, float lh, boolean medium) { this.sp = sp; this.lh = lh; this.medium = medium; }
    }
    public static final Type TITLE_L = new Type(22, 28, true), TITLE_M = new Type(18, 24, true),
            BODY_L = new Type(16, 22, false), BODY_M = new Type(14, 20, false),
            LABEL_L = new Type(15, 20, true), LABEL_M = new Type(13, 18, true),
            CAPTION = new Type(12, 16, false), NUMERAL = new Type(28, 32, true);

    /** Medium (500) face: the real weight on Android 9+, sans-serif-medium before. */
    public static Typeface medium() {
        if (android.os.Build.VERSION.SDK_INT >= 28) return Typeface.create(Typeface.DEFAULT, 500, false);
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    public static TextView text(Context c, CharSequence s, Type t, int color) {
        TextView v = text(c, s, t.sp, color);
        style(v, t);
        return v;
    }

    /** Apply a type role to an existing view (size, weight, line height). */
    public static void style(TextView v, Type t) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.sp);
        v.setTypeface(t.medium ? medium() : Typeface.DEFAULT);
        float lhPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, t.lh, v.getResources().getDisplayMetrics());
        if (android.os.Build.VERSION.SDK_INT >= 28) v.setLineHeight(Math.round(lhPx));
        else v.setLineSpacing(0f, t.lh / t.sp);
        if (t == NUMERAL) tnum(v);
    }

    /** Numbers that change (speed, seconds, version, MB, counts) keep their width. */
    public static void tnum(TextView v) { v.setFontFeatureSettings("tnum"); }

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
    public static final int ROW_SUB_DP = 64;   // a row with an explanation line
    public static final int CARD_RADIUS_DP = 20;
    public static final int ANIM_MS = 200;

    private Ui() {}

    /**
     * Fill accent (103): a colour the user picked in an older version keeps working; nothing stored or
     * the old brand blue #2F6BFF means the brand red (the key is never rewritten).
     */
    public static int accent(Context c) {
        String a = Prefs.str(c, Prefs.ACCENT, "");
        if (Logic.isDefaultAccent(a)) return DEFAULT_ACCENT;
        try { if (a.matches("#[0-9a-fA-F]{6}")) return 0xFF000000 | Integer.parseInt(a.substring(1), 16); } catch (Exception ignored) {}
        return DEFAULT_ACCENT;
    }

    /** Red for text / icons / dots on dark surfaces: always the bright ink, a picked colour only fills (G1). */
    public static int accentText(Context c) {
        return ACCENT_INK;
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
        style(b, LABEL_L);
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setBackground(round(accent(c), dp(c, 24)));
        pressScale(b);
        return b;
    }

    /** Text-only accent button (357): the secondary action next to a filled one. */
    public static android.widget.Button textButton(Context c, String label) {
        android.widget.Button b = new android.widget.Button(c, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(accentText(c));
        style(b, LABEL_L);
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        pressScale(b);
        return b;
    }

    /** "#RRGGBB" to an opaque colour, the default accent when it is not one. */
    public static int parseColor(String hex) {
        try { if (hex != null && hex.matches("#[0-9a-fA-F]{6}")) return 0xFF000000 | Integer.parseInt(hex.substring(1), 16); } catch (Exception ignored) {}
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

    /** Group title over a card (v1.24): label-medium, secondary grey, no accent; far from the card above, near its own. */
    public static TextView title(Context c, CharSequence s) {
        TextView t = text(c, s, LABEL_M, TEXT_2);
        t.setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 8));
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

    // ---- motion (v1.25/v1.27, DESIGN.md "Motion"): only transform and opacity, all interruptible ----
    public static final int SHEET_IN = 360, SHEET_OUT = 240, SHEET_BACK = 200;
    public static final int PRESS_IN = 100, PRESS_OUT = 160, SNACK_IN = 220, SNACK_OUT = 160, PAGE_IN = 260, PAGE_OUT = 200, SWITCH_MS = 150;
    public static final int SCRIM_BLACK = 0x9E000000;   // 62 % on pure black
    public static final int SCRIM_DARK = 0x80000000;    // 50 % on dark grey

    /** Drawer curve (0.32, 0.72, 0, 1): sheets in and out. */
    public static android.view.animation.Interpolator drawer() {
        return new android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f);
    }

    /** Ease-in-out (0.77, 0, 0.175, 1): things moving on screen (a selected segment). */
    public static android.view.animation.Interpolator easeInOut() {
        return new android.view.animation.PathInterpolator(0.77f, 0f, 0.175f, 1f);
    }

    /**
     * Press feedback (v1.27) for buttons, segments and the seek buttons, not for list rows (they keep
     * the platform ripple): scale .96 in 100 ms, back in 160 ms, strong ease-out. Nothing when the
     * system's "remove animations" is on.
     */
    public static void pressScale(View v) {
        if (Prefs.animationsOff(v.getContext())) return;
        android.animation.StateListAnimator sla = new android.animation.StateListAnimator();
        sla.addState(new int[]{android.R.attr.state_pressed}, scaleTo(v, 0.96f, PRESS_IN));
        sla.addState(new int[]{}, scaleTo(v, 1f, PRESS_OUT));
        v.setStateListAnimator(sla);
    }

    private static android.animation.Animator scaleTo(View v, float to, int ms) {
        android.animation.AnimatorSet set = new android.animation.AnimatorSet();
        set.playTogether(android.animation.ObjectAnimator.ofFloat(v, "scaleX", to),
                android.animation.ObjectAnimator.ofFloat(v, "scaleY", to));
        set.setDuration(ms);
        set.setInterpolator(easeOut());
        return set;
    }

    /** Strong ease-out (0.23, 1, 0.32, 1): entering things, press release, snack bars. */
    public static android.view.animation.Interpolator easeOut() {
        return new android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f);
    }

    /**
     * Bottom sheet (165/v1.25), still a Dialog so playback, background quality and the page script are
     * untouched (no new Activity, no onStop). Full-screen transparent window with its own scrim; the
     * sheet slides up, follows the finger when dragged down from the handle or from the top of its
     * content, and slides out on dismiss (every dismiss: callers keep calling dismiss()). "Remove
     * animations" makes all of it instant.
     */
    public static Dialog sheet(Activity a, View content) {
        return new Sheet(a, content);
    }

    /** The sheet window; dismiss() plays the exit and only then really closes. */
    public static final class Sheet extends Dialog {
        private final FrameLayout root;
        private final View scrim;
        private final LinearLayout panel;
        private final DragScroll scroll;
        private boolean closing;
        private boolean outsideCancels = true;
        private boolean cancelable = true;
        private float downY, startT;
        private boolean dragging;
        private android.view.VelocityTracker vt;

        Sheet(Activity a, View content) {
            super(a, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
            setOwnerActivity(a);
            root = new FrameLayout(a);
            scrim = new View(a);
            scrim.setBackgroundColor(amoled(a) ? SCRIM_BLACK : SCRIM_DARK);
            scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            scrim.setOnClickListener(v -> { if (outsideCancels && cancelable) cancel(); });
            root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

            panel = new LinearLayout(a);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setClickable(true);   // taps on the sheet never reach the scrim
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(sheetBg(a));
            float r = dp(a, 28);
            bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            panel.setBackground(bg);
            if (android.os.Build.VERSION.SDK_INT >= 29) panel.setForceDarkAllowed(false);   // (392)
            // handle: 32 x 4 dp, in a 24 dp tall strip that also takes the drag
            FrameLayout grip = new FrameLayout(a);
            View handle = new View(a);
            handle.setBackground(round(HANDLE, dp(a, 2)));
            grip.addView(handle, new FrameLayout.LayoutParams(dp(a, 32), dp(a, 4), Gravity.CENTER));
            grip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            panel.addView(grip, new LinearLayout.LayoutParams(-1, dp(a, 24)));
            if (amoled(a)) {   // a black sheet still shows its top edge
                View edge = new View(a);
                edge.setBackgroundColor(HAIRLINE_STRONG);
                FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(-1, Math.max(1, dp(a, 1)), Gravity.TOP);
                grip.addView(edge, ep);
                grip.setClipToOutline(false);
            }
            scroll = new DragScroll(a, this);
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, 0, 0, dp(a, 28));
            box.addView(content);
            scroll.addView(box);
            panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(sheetWidth(a), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            pp.topMargin = dp(a, 48);   // the scrim always shows above a tall sheet
            root.addView(panel, pp);
            grip.setOnTouchListener((v, ev) -> canDrag() && drag(ev, true));
            // navigation bar inset under the content (gesture or button navigation)
            root.setOnApplyWindowInsetsListener((v, ins) -> {
                panel.setPadding(0, 0, 0, ins.getSystemWindowInsetBottom());
                return ins;
            });
            setContentView(root);
            Window w = getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                w.setWindowAnimations(0);
                w.setGravity(Gravity.BOTTOM);
            }
        }

        /** Not cancelable (a download in progress): no back, no scrim tap, no drag to close. */
        @Override public void setCancelable(boolean flag) {
            super.setCancelable(flag);
            cancelable = flag;
        }

        boolean canDrag() { return cancelable && !closing; }

        @Override public void setCanceledOnTouchOutside(boolean cancel) {
            super.setCanceledOnTouchOutside(cancel);
            outsideCancels = cancel;
        }

        @Override public void show() {
            super.show();
            closing = false;
            if (Prefs.animationsOff(getContext())) return;
            panel.setVisibility(View.INVISIBLE);
            scrim.setAlpha(0f);
            panel.post(() -> {
                float h = panel.getHeight();
                panel.setTranslationY(h);
                panel.setVisibility(View.VISIBLE);
                panel.animate().translationY(0f).setDuration(SHEET_IN).setInterpolator(drawer()).start();
                scrim.animate().alpha(1f).setDuration(SHEET_IN).setInterpolator(drawer()).start();
            });
        }

        @Override public void dismiss() {
            if (closing) return;
            Activity owner = getOwnerActivity();
            if (!isShowing() || Prefs.animationsOff(getContext()) || (owner != null && owner.isFinishing())
                    || panel.getHeight() == 0) { reallyDismiss(); return; }
            closing = true;
            float h = panel.getHeight();
            float to = Math.max(panel.getTranslationY(), h * 0.6f);
            panel.animate().cancel();
            scrim.animate().cancel();
            panel.animate().translationY(to).alpha(0f).setDuration(SHEET_OUT).setInterpolator(drawer())
                    .withEndAction(this::reallyDismiss).start();
            scrim.animate().alpha(0f).setDuration(SHEET_OUT).setInterpolator(drawer()).start();
            // never stay open if the animation is dropped (window detached mid-way)
            panel.postDelayed(this::reallyDismiss, SHEET_OUT + 150);
        }

        private void reallyDismiss() {
            try { if (isShowing()) super.dismiss(); } catch (Exception ignored) {}
        }

        /** Velocity in screen coordinates: the sheet itself moves under the finger. */
        private void track(android.view.MotionEvent ev) {
            android.view.MotionEvent c = android.view.MotionEvent.obtain(ev);
            c.setLocation(ev.getRawX(), ev.getRawY());
            vt.addMovement(c);
            c.recycle();
        }

        /** Shared by the handle strip and the content's top edge: 1:1 down, 0.2 damping up. */
        boolean drag(android.view.MotionEvent ev, boolean fromGrip) {
            if (closing) return true;
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downY = ev.getRawY();
                    startT = panel.getTranslationY();
                    panel.animate().cancel();
                    scrim.animate().cancel();
                    if (vt != null) vt.recycle();
                    vt = android.view.VelocityTracker.obtain();
                    track(ev);
                    dragging = !fromGrip;   // content drags start only after its slop check
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (vt == null) { vt = android.view.VelocityTracker.obtain(); downY = ev.getRawY(); startT = panel.getTranslationY(); }
                    track(ev);
                    float dy = ev.getRawY() - downY + startT;
                    float t = dy >= 0 ? dy : dy * 0.2f;
                    panel.setTranslationY(t);
                    float h = Math.max(1, panel.getHeight());
                    scrim.setAlpha(Math.max(0f, Math.min(1f, 1f - Math.max(0f, t) / h)));
                    dragging = true;
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    float vy = 0;
                    if (vt != null) { track(ev); vt.computeCurrentVelocity(1000); vy = vt.getYVelocity(); vt.recycle(); vt = null; }
                    float t = panel.getTranslationY(), h = Math.max(1, panel.getHeight());
                    boolean wasDrag = dragging;
                    dragging = false;
                    if (!wasDrag && Math.abs(t) < 1f) return true;
                    float dens = getContext().getResources().getDisplayMetrics().density;
                    if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP && Logic.sheetShouldDismiss(t, h, vy, dens)) {
                        cancel();
                    } else {
                        int ms = anim(getContext(), SHEET_BACK);
                        panel.animate().translationY(0f).setDuration(ms).setInterpolator(easeOut()).start();
                        scrim.animate().alpha(1f).setDuration(ms).setInterpolator(easeOut()).start();
                    }
                    return true;
                }
                default:
                    return true;
            }
        }
    }

    /** The sheet's scroll view: a downward pull while it is at the top drags the whole sheet instead. */
    static final class DragScroll extends ScrollView {
        private final Sheet sheet;
        private final int slop;
        private float x0, y0;
        private boolean pulling;

        DragScroll(Context c, Sheet s) {
            super(c);
            sheet = s;
            slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
            setOverScrollMode(OVER_SCROLL_NEVER);
            if (android.os.Build.VERSION.SDK_INT >= 29) setForceDarkAllowed(false);
        }

        @Override public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    x0 = ev.getRawX(); y0 = ev.getRawY(); pulling = false;
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dy = ev.getRawY() - y0, dx = Math.abs(ev.getRawX() - x0);
                    if (!pulling && sheet.canDrag() && getScrollY() == 0 && dy > slop && dy > dx
                            && !innerScrollsUp(this, (int) ev.getRawX(), (int) ev.getRawY())) {
                        pulling = true;
                        android.view.MotionEvent down = android.view.MotionEvent.obtain(ev);
                        down.setAction(android.view.MotionEvent.ACTION_DOWN);
                        sheet.drag(down, false);
                        down.recycle();
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return super.onInterceptTouchEvent(ev);
        }

        /** A scrolling box inside the sheet (release notes) that can still scroll up keeps the gesture. */
        private static boolean innerScrollsUp(View v, int rx, int ry) {
            if (!(v instanceof ViewGroup)) return false;
            ViewGroup g = (ViewGroup) v;
            int[] at = new int[2];
            for (int i = 0; i < g.getChildCount(); i++) {
                View ch = g.getChildAt(i);
                if (ch.getVisibility() != View.VISIBLE) continue;
                ch.getLocationOnScreen(at);
                if (rx < at[0] || ry < at[1] || rx >= at[0] + ch.getWidth() || ry >= at[1] + ch.getHeight()) continue;
                if (ch.canScrollVertically(-1)) return true;
                if (innerScrollsUp(ch, rx, ry)) return true;
            }
            return false;
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent ev) {
            if (pulling) {
                boolean r = sheet.drag(ev, false);
                int act = ev.getActionMasked();
                if (act == android.view.MotionEvent.ACTION_UP || act == android.view.MotionEvent.ACTION_CANCEL) pulling = false;
                return r;
            }
            return super.onTouchEvent(ev);
        }
    }

    // ---- one look for confirmations, form sheets and the text editor (v1.25) ----

    /** Title line of a sheet: title-medium, 24 dp in from the sides. */
    public static TextView sheetTitle(Context c, String title) {
        TextView head = text(c, title, TITLE_M, TEXT);
        head.setPadding(dp(c, 24), dp(c, 4), dp(c, 24), dp(c, 12));
        heading(head);
        return head;
    }

    /** Action line at the bottom of a sheet: an optional text button left, the filled main button right. */
    private static LinearLayout actions(final Dialog d, Context c, String secondaryLabel, final Runnable secondary,
                                        String primaryLabel, final Runnable primary) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.setPadding(dp(c, 16), dp(c, 16), dp(c, 24), 0);
        if (secondaryLabel != null) {
            android.widget.Button s = textButton(c, secondaryLabel);
            s.setTextColor(TEXT_2);
            s.setOnClickListener(v -> { d.dismiss(); if (secondary != null) secondary.run(); });
            row.addView(s, new LinearLayout.LayoutParams(-2, -2));
        }
        android.widget.Button p = filledButton(c, primaryLabel);
        p.setPadding(dp(c, 24), 0, dp(c, 24), 0);
        p.setOnClickListener(v -> { d.dismiss(); if (primary != null) primary.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(c, 48));
        lp.leftMargin = dp(c, 8);
        row.addView(p, lp);
        return row;
    }

    /** Confirmation as a sheet: title, a sentence, main button + text button ("清空" / "取消"). */
    public static Dialog confirmSheet(Activity a, String title, CharSequence body, String primaryLabel, Runnable primary,
                                      String secondaryLabel, Runnable secondary) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sheetTitle(a, title));
        if (body != null) {
            TextView t = text(a, body, BODY_M, TEXT_2);
            t.setPadding(dp(a, 24), 0, dp(a, 24), 0);
            box.addView(t);
        }
        Dialog d = sheet(a, box);
        box.addView(actions(d, a, secondaryLabel, secondary, primaryLabel, primary));
        d.show();
        return d;
    }

    /**
     * A sheet with a title, any form view (radio group, check boxes) and 保存/取消 actions: the
     * choices only count once saved, as before (v1.25 changes the frame, not the behaviour).
     */
    public static Dialog formSheet(Activity a, String title, View body, String primaryLabel, Runnable primary) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sheetTitle(a, title));
        box.addView(body);
        Dialog d = sheet(a, box);
        box.addView(actions(d, a, "取消", null, primaryLabel, primary));
        d.show();
        return d;
    }

    public interface SwitchRow { String title(); String sub(); boolean get(); void set(boolean on); }

    /** Several switches in one sheet, each takes effect at once (no OK button), then {@code changed} runs. */
    public static Dialog switchSheet(Activity a, String title, final Runnable changed, SwitchRow... rows) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sheetTitle(a, title));
        for (final SwitchRow r : rows) {
            final LinearLayout l = new LinearLayout(a);
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setPadding(dp(a, 24), dp(a, 12), dp(a, 20), dp(a, 12));
            l.setMinimumHeight(dp(a, r.sub() == null ? 56 : 64));
            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(text(a, r.title(), BODY_L, TEXT));
            if (r.sub() != null) texts.addView(text(a, r.sub(), BODY_M, TEXT_2));
            l.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            final android.widget.Switch sw = new android.widget.Switch(a);
            sw.setChecked(r.get());
            styleSwitch(sw);
            sw.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            sw.setFocusable(false);
            l.setContentDescription(r.title() + (r.sub() == null ? "" : "，" + r.sub()));
            if (android.os.Build.VERSION.SDK_INT >= 30) l.setStateDescription(sw.isChecked() ? "已开启" : "已关闭");
            sw.setOnCheckedChangeListener((b, on) -> {
                r.set(on);
                if (android.os.Build.VERSION.SDK_INT >= 30) l.setStateDescription(on ? "已开启" : "已关闭");
                haptic(l, HAPTIC_CONFIRM);
                if (changed != null) changed.run();
            });
            l.addView(sw);
            ripple(l);
            l.setOnClickListener(v -> sw.toggle());
            box.addView(l);
        }
        Dialog d = sheet(a, box);
        d.show();
        return d;
    }

    /** Light haptic of a kind (372), only while haptics are on (the stored 轻震 value, default on). */
    public static void haptic(View v, int kind) {
        try { if (Prefs.on(v.getContext(), Prefs.HAPTIC)) v.performHapticFeedback(hapticConstant(kind)); } catch (Throwable ignored) {}
    }

    /**
     * Centred dialog (v1.25), only for multi-line text editing where a draggable sheet and the keyboard
     * would push each other: surface 2, 28 dp corners, title, the view, 取消 / 保存 at the bottom right.
     */
    public static Dialog dialog(Activity a, String title, View body, String primaryLabel, final Runnable primary) {
        final Dialog d = new Dialog(a, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(a, 24), 0, dp(a, 16));
        GradientDrawable bg = round(sheetBg(a), dp(a, 28));
        if (amoled(a)) bg.setStroke(Math.max(1, dp(a, 1)), HAIRLINE_STRONG);
        box.setBackground(bg);
        box.addView(sheetTitle(a, title));
        box.addView(body);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), 0);
        android.widget.Button cancel = textButton(a, "取消");
        cancel.setTextColor(TEXT_2);
        cancel.setOnClickListener(v -> d.dismiss());
        android.widget.Button ok = textButton(a, primaryLabel);
        ok.setOnClickListener(v -> { d.dismiss(); if (primary != null) primary.run(); });
        row.addView(cancel);
        row.addView(ok);
        box.addView(row);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            int wdp = a.getResources().getConfiguration().screenWidthDp;
            w.setLayout(wdp > 600 ? dp(a, 560) : a.getResources().getDisplayMetrics().widthPixels - dp(a, 48), ViewGroup.LayoutParams.WRAP_CONTENT);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = amoled(a) ? 0.62f : 0.5f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            if (!Prefs.animationsOff(a)) {
                box.setAlpha(0f);
                box.setScaleX(0.96f);
                box.setScaleY(0.96f);
                box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(easeOut()).start();
            }
        }
        d.show();
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

    /** Section header inside a sheet (352/v1.24): label-medium, secondary grey, heading for screen readers. */
    public static TextView header(Context c, String label) {
        TextView t = text(c, label, LABEL_M, TEXT_2);
        t.setPadding(dp(c, 24), dp(c, 16), dp(c, 24), dp(c, 8));
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
        style(b, LABEL_L);
        b.setBackground(round(chipBg(c), dp(c, 12)));
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setMaxLines(2);
        b.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
        b.setOnClickListener(click);
        pressScale(b);
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
        box.addView(sheetTitle(a, title));
        final Dialog[] d = new Dialog[1];
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            LinearLayout l = new LinearLayout(a);
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setPadding(dp(a, 24), dp(a, 8), dp(a, 20), dp(a, 8));
            l.setMinimumHeight(dp(a, 56));
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
            TextView t = text(a, labels[i], BODY_L, TEXT);
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
     * Material 3 switch (355/v1.24): 52 × 32 dp; on = brand-red track + 24 dp white knob, off = 2 dp grey
     * outline + 16 dp grey knob. Still the platform Switch underneath.
     */
    public static void styleSwitch(android.widget.Switch sw) {
        try {
            Context c = sw.getContext();
            int acc = accent(c);
            android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
            GradientDrawable on = round(acc, dp(c, 16));
            on.setSize(dp(c, 52), dp(c, 32));
            GradientDrawable off = round(0x00000000, dp(c, 16));
            off.setStroke(dp(c, 2), SWITCH_OFF);
            off.setSize(dp(c, 52), dp(c, 32));
            track.addState(new int[]{android.R.attr.state_checked}, on);
            track.addState(new int[]{}, off);
            android.graphics.drawable.StateListDrawable thumb = new android.graphics.drawable.StateListDrawable();
            thumb.addState(new int[]{android.R.attr.state_checked}, knob(c, SWITCH_THUMB, 4));
            thumb.addState(new int[]{}, knob(c, SWITCH_OFF, 8));
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
        texts.addView(text(c, title, BODY_L, TEXT));
        boolean hasSub = sub != null && !sub.isEmpty();
        if (hasSub) texts.addView(text(c, sub, BODY_M, TEXT_2));
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(dp(c, 24), dp(c, 12), dp(c, 24), dp(c, 12));
        l.setMinimumHeight(dp(c, hasSub ? 64 : 56));
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
