package com.google.android.youtube.pro;

import android.app.Dialog;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Native bottom sheet with the playback tools (button next to the settings gear):
 * -10 / +30 s, fine speed (151), single-video loop (152), black screen (157), touch lock (131).
 * A-B loop, playlist shuffle and the orientation lock were removed in v1.10 (450/451/452), the sleep
 * timer and everything around it in v1.15.
 */
public final class PlayerTools {
    private final MainActivity a;
    private JSONObject st;
    private Dialog dialog;
    /** What the shared sheet frame (352) holds; redrawn in place after a toggle (335). */
    private LinearLayout holder;

    private PlayerTools(MainActivity a, JSONObject st) {
        this.a = a;
        this.st = st == null ? new JSONObject() : st;
    }

    public static void show(MainActivity a, JSONObject state) {
        new PlayerTools(a, state).build();
    }

    /** The same bottom-sheet frame as every other sheet (352): handle, corners, dim, width on tablets. */
    private void build() {
        holder = new LinearLayout(a);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.addView(content());
        dialog = Ui.sheet(a, holder);
        dialog.show();
    }

    private static final double[] SPEEDS = {0.75, 1.0, 1.25, 1.5, 2.0};
    private TextView rateValue;
    private final List<TextView> segs = new ArrayList<>();

    /**
     * v1.26 layout (functions unchanged): two big seek buttons, speed with a five-step segmented
     * control and "微调", single-video loop as a switch row, then the screen group. A change updates
     * the views in place instead of rebuilding the sheet.
     */
    private LinearLayout content() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.sheetTitle(a, "播放工具"));
        segs.clear();

        boolean watch = st.optBoolean("watch", false);
        if (watch) {
            LinearLayout seekRow = new LinearLayout(a);
            seekRow.setOrientation(LinearLayout.HORIZONTAL);
            seekRow.setPadding(dp(20), 0, dp(20), dp(8));
            seekRow.addView(seekButton("−10 秒", "快退 10 秒", R.drawable.ic_rotate_ccw, -10), seekLp());
            seekRow.addView(seekButton("+30 秒", "快进 30 秒", R.drawable.ic_rotate_cw, 30), seekLp());
            box.addView(seekRow);

            final double rate = st.optDouble("rate", 1);
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(dp(24), dp(12), dp(24), dp(8));
            TextView ht = Ui.text(a, "播放速度", Ui.BODY_L, Ui.TEXT);
            Ui.heading(ht);
            head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1f));
            rateValue = Ui.text(a, fmtRate(rate), Ui.NUMERAL, Ui.TEXT);
            head.addView(rateValue);
            box.addView(head);

            LinearLayout seg = new LinearLayout(a);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setPadding(dp(4), dp(4), dp(4), dp(4));
            seg.setBackground(Ui.round(Ui.chipBg(a), dp(12)));
            for (final double r : SPEEDS) {
                final TextView t = Ui.text(a, fmtShort(r), Ui.LABEL_L, Ui.TEXT);
                Ui.tnum(t);
                t.setGravity(Gravity.CENTER);
                t.setTag(r);
                t.setMinHeight(dp(40));
                Ui.pressScale(t);
                t.setOnClickListener(v -> {
                    send(r);
                    showRate(r);
                    Ui.haptic(v, Ui.HAPTIC_TAP);
                });
                segs.add(t);
                seg.addView(t, new LinearLayout.LayoutParams(0, dp(40), 1f));
            }
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.setMargins(dp(24), 0, dp(24), 0);
            box.addView(seg, sp);
            showRate(rate);

            LinearLayout fine = new LinearLayout(a);
            fine.setOrientation(LinearLayout.HORIZONTAL);
            fine.setGravity(Gravity.CENTER_VERTICAL);
            fine.setPadding(dp(24), 0, dp(12), 0);
            fine.addView(Ui.text(a, "0.25–2 倍，每档 0.05", Ui.BODY_M, Ui.TEXT_2), new LinearLayout.LayoutParams(0, -2, 1f));
            Button fb = Ui.textButton(a, "微调");
            fb.setOnClickListener(v -> { double cur = rateNow(rate); dismiss(); pickSpeed(cur); });
            fine.addView(fb);
            box.addView(fine);

            box.addView(loopRow(st.optBoolean("loop", false)));
        }
        box.addView(Ui.header(a, "屏幕"));
        box.addView(Ui.sheetRow(a, R.drawable.ic_monitor_off, "黑屏播放", "屏幕全黑不锁屏，OLED 几乎不耗电；双击恢复", v -> { dismiss(); a.startBlackScreen(); }));
        box.addView(Ui.sheetRow(a, R.drawable.ic_lock, "触控锁", "防误触；长按屏幕 1.5 秒解锁", v -> { dismiss(); a.startTouchLock(); }));
        return box;
    }

    private double shownRate = 1;

    private double rateNow(double fallback) { return rateValue == null ? fallback : shownRate; }

    /** Current speed in the big number; the matching segment filled brand red. */
    private void showRate(double r) {
        shownRate = r;
        if (rateValue != null) rateValue.setText(fmtRate(r));
        for (TextView t : segs) {
            boolean on = Math.abs((Double) t.getTag() - r) < 0.001;
            t.setBackground(on ? Ui.round(Ui.accent(a), dp(8)) : null);
            t.setTextColor(on ? Ui.WHITE : Ui.TEXT);
            if (android.os.Build.VERSION.SDK_INT >= 30) t.setStateDescription(on ? "已选中" : "未选中");
            t.setContentDescription(t.getText() + " 倍速");
        }
    }

    /** Single-video loop as a switch row; the repeat icon turns bright red while on. */
    private View loopRow(boolean on) {
        final LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(24), dp(12), dp(20), dp(12));
        l.setMinimumHeight(dp(64));
        final android.widget.ImageView ic = new android.widget.ImageView(a);
        ic.setImageResource(R.drawable.ic_repeat_one);
        ic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(24), dp(24));
        ip.rightMargin = dp(16);
        l.addView(ic, ip);
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(a, "单曲循环", Ui.BODY_L, Ui.TEXT));
        texts.addView(Ui.text(a, "反复播放当前视频", Ui.BODY_M, Ui.TEXT_2));
        l.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        final android.widget.Switch sw = new android.widget.Switch(a);
        sw.setChecked(on);
        Ui.styleSwitch(sw);
        sw.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sw.setFocusable(false);
        final Runnable paint = () -> {
            ic.setImageTintList(android.content.res.ColorStateList.valueOf(sw.isChecked() ? Ui.ACCENT_INK : Ui.TEXT_2));
            if (android.os.Build.VERSION.SDK_INT >= 30) l.setStateDescription(sw.isChecked() ? "已开启" : "已关闭");
        };
        paint.run();
        l.setContentDescription("单曲循环，反复播放当前视频");
        sw.setOnCheckedChangeListener((b, checked) -> {
            MainActivity.js("ytl.loop(" + checked + ")");
            paint.run();
            Ui.haptic(l, Ui.HAPTIC_CONFIRM);
        });
        l.addView(sw);
        Ui.ripple(l);
        l.setOnClickListener(v -> sw.toggle());
        return l;
    }

    private LinearLayout.LayoutParams seekLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    // ---- -10 / +30 s (342): presses within 1.5 s add up, the button shows the total for a moment ----
    private int seekSum = 0;
    private LinearLayout seekShown;
    private final Runnable seekReset = () -> {
        seekSum = 0;
        if (seekShown != null) {
            TextView t = (TextView) seekShown.getChildAt(1);
            t.setText((String) t.getTag());
            tint(seekShown, false);
            seekShown = null;
        }
    };

    /** 56 dp tonal button, 16 dp corners, line rotate icon and label centred; accent tint while presses add up. */
    private View seekButton(String label, String spoken, int icon, final int sec) {
        final LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        tint(b, false);
        android.widget.ImageView iv = new android.widget.ImageView(a);
        iv.setImageResource(icon);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT));
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(20), dp(20));
        ip.rightMargin = dp(8);
        b.addView(iv, ip);
        final TextView t = Ui.text(a, label, Ui.LABEL_L, Ui.TEXT);
        Ui.tnum(t);
        t.setTag(label);
        b.addView(t);
        b.setContentDescription(spoken);
        b.setFocusable(true);
        b.setClickable(true);
        Ui.pressScale(b);
        b.setOnClickListener(v -> {
            MainActivity.js("ytl.seekBy(" + sec + ")");
            if (seekShown != null && seekShown != b) seekReset.run();
            seekSum += sec;
            seekShown = b;
            t.setText(seekSum < 0 ? "−" + (-seekSum) + " 秒" : "+" + seekSum + " 秒");
            tint(b, true);
            MainActivity.MAIN_HANDLER.removeCallbacks(seekReset);
            MainActivity.MAIN_HANDLER.postDelayed(seekReset, 1500);
        });
        return b;
    }

    /** Background with a ripple on top: surface 3, or the accent tint while presses add up. */
    private void tint(LinearLayout b, boolean adding) {
        b.setBackground(Ui.round(adding ? Ui.ACCENT_TINT : Ui.chipBg(a), dp(16)));
        if (b.getForeground() == null) {
            android.util.TypedValue tv = new android.util.TypedValue();
            a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            try { b.setForeground(a.getDrawable(tv.resourceId)); } catch (Exception ignored) {}
            b.setClipToOutline(true);
        }
    }

    private void dismiss() {
        try { if (dialog != null) dialog.dismiss(); } catch (Exception ignored) {}
    }

    // ===================== sub dialogs =====================

    /**
     * Speed (334): a bottom sheet like the others, common speeds as chips, the fine slider underneath.
     * Dragging sends at most one change per 100 ms; letting go always sends the final value.
     */
    private void pickSpeed(double current) {
        final int min = 5, max = 40; // x 0.05
        int cur = (int) Math.round(Math.max(0.25, Math.min(2, current)) / 0.05);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), 0, dp(12), dp(8));
        TextView head = Ui.sheetTitle(a, "播放速度");
        head.setPadding(dp(12), dp(4), dp(12), dp(4));
        box.addView(head);
        final TextView label = Ui.text(a, fmtRate(cur * 0.05), Ui.NUMERAL, Ui.TEXT);
        label.setGravity(Gravity.CENTER);
        box.addView(label);
        final SeekBar bar = new SeekBar(a);
        bar.setMax(max - min);
        bar.setProgress(cur - min);
        bar.setContentDescription("播放速度");
        bar.setProgressTintList(Ui.accentList(a));
        bar.setThumbTintList(Ui.accentList(a));
        final long[] lastSent = {0};
        final Runnable[] sendLater = new Runnable[1];
        final double[] pending = {cur * 0.05};
        sendLater[0] = () -> { send(pending[0]); lastSent[0] = android.os.SystemClock.uptimeMillis(); };
        LinearLayout common = new LinearLayout(a);
        common.setOrientation(LinearLayout.HORIZONTAL);
        for (final double r : new double[]{0.75, 1.0, 1.25, 1.5, 2.0}) {
            common.addView(Ui.chip(a, fmtShort(r), v -> bar.setProgress((int) Math.round(r / 0.05) - min)), Ui.chipWeight(a));
        }
        box.addView(common);
        box.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(Ui.chip(a, "−0.05", v -> bar.setProgress(Math.max(0, bar.getProgress() - 1))), Ui.chipWeight(a));
        btns.addView(Ui.chip(a, "+0.05", v -> bar.setProgress(Math.min(max - min, bar.getProgress() + 1))), Ui.chipWeight(a));
        box.addView(btns);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && android.os.Build.VERSION.SDK_INT >= 27) s.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                double r = (p + min) * 0.05;
                label.setText(fmtRate(r));
                pending[0] = r;
                long now = android.os.SystemClock.uptimeMillis();
                MainActivity.MAIN_HANDLER.removeCallbacks(sendLater[0]);
                if (now - lastSent[0] >= 100) sendLater[0].run();
                else MainActivity.MAIN_HANDLER.postDelayed(sendLater[0], 100 - (now - lastSent[0]));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                MainActivity.MAIN_HANDLER.removeCallbacks(sendLater[0]);
                sendLater[0].run();
            }
        });
        Dialog d = Ui.sheet(a, box);
        d.setOnDismissListener(x -> MainActivity.MAIN_HANDLER.removeCallbacks(sendLater[0]));
        d.show();
    }

    private static void send(double r) {
        MainActivity.js("ytl.setRate(" + String.format(java.util.Locale.US, "%.2f", r) + ")");
    }

    private static String fmtShort(double r) {
        String s = String.format(java.util.Locale.US, "%.2f", r).replaceAll("0+$", "").replaceAll("\\.$", ".0");
        return s + "x";
    }

    // ===================== views =====================

    private static String fmtRate(double r) {
        return String.format(java.util.Locale.US, "%.2fx", r);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
