package com.google.android.youtube.pro;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.pm.ActivityInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Native bottom sheet with the playback tools (moon button next to the settings gear):
 * sleep timer, black screen (157), touch lock (131), orientation lock (132),
 * fine speed (151), single-video / A-B loop (152), playlist shuffle (153), -10 / +30 s.
 */
public final class PlayerTools {
    private final MainActivity a;
    private JSONObject st;
    private Dialog dialog;
    private ScrollView scroll;

    private PlayerTools(MainActivity a, JSONObject st) {
        this.a = a;
        this.st = st == null ? new JSONObject() : st;
    }

    public static void show(MainActivity a, JSONObject state) {
        new PlayerTools(a, state).build();
    }

    /** Toggles stay in the sheet (335): the page state is read again and the rows are redrawn in place. */
    private void refresh() {
        MainActivity.MAIN_HANDLER.postDelayed(() -> a.toolState(s -> {
            if (dialog == null || !dialog.isShowing()) return;
            st = s == null ? new JSONObject() : s;
            scroll.removeAllViews();
            scroll.addView(content());
        }), 120);
    }

    private void build() {
        scroll = new ScrollView(a);
        scroll.addView(content());
        dialog = new Dialog(a, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        dialog.setContentView(scroll);
        dialog.setCanceledOnTouchOutside(true);
        Window w = dialog.getWindow();
        if (w != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Ui.SHEET);
            float r = dp(16);
            bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            w.setBackgroundDrawable(bg);
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (!Prefs.animationsOff(a)) w.setWindowAnimations(android.R.style.Animation_InputMethod);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.4f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
    }

    private LinearLayout content() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(16));

        TextView handle = new TextView(a);
        handle.setBackground(Ui.round(Ui.HANDLE, dp(2)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(6);
        box.addView(handle, hp);

        box.addView(header("睡眠"));
        String sleep = SleepTimer.active() ? "睡眠定时：" + SleepTimer.optionLabel(SleepTimer.mode()) : "睡眠定时…";
        box.addView(row(sleep, SleepTimer.active() ? SleepTimer.status() + " · 点此改时长或关闭" : "到点淡出后暂停", v -> {
            dismiss();
            pickSleep(a, "睡眠定时", m -> SleepTimer.start(a, m));
        }));
        if (!SleepTimer.active()) {
            box.addView(row("一键睡眠模式", "定时 " + SleepTimer.optionLabel(Prefs.sleepDefault(a)) + " + 最低画质 + 只续播同频道 + 调暗屏幕",
                    v -> { dismiss(); a.startSleepMode(); }));
        }
        box.addView(row("黑屏播放", "屏幕全黑不锁屏，OLED 几乎不耗电；双击恢复", v -> { dismiss(); a.startBlackScreen(); }));
        box.addView(row("触控锁", "睡前看视频防误触；长按屏幕 1.5 秒解锁", v -> { dismiss(); a.startTouchLock(); }));

        boolean watch = st.optBoolean("watch", false);
        if (watch) {
            box.addView(header("播放"));
            LinearLayout seekRow = new LinearLayout(a);
            seekRow.setOrientation(LinearLayout.HORIZONTAL);
            seekRow.setPadding(dp(12), dp(4), dp(12), dp(4));
            seekRow.addView(seekChip("快退 10 秒", -10), weight());
            seekRow.addView(seekChip("快进 30 秒", 30), weight());
            box.addView(seekRow);

            double rate = st.optDouble("rate", 1);
            box.addView(row("播放速度：" + fmtRate(rate), "0.25–2 倍，每档 0.05", v -> { dismiss(); pickSpeed(rate); }));

            boolean loop = st.optBoolean("loop", false);
            box.addView(row(loop ? "单曲循环：开 ✓" : "单曲循环：关", "睡前循环听同一个视频", v -> {
                MainActivity.js("ytl.loop(" + !loop + ")");
                refresh();
            }));

            double ab_a = st.optDouble("a", -1), ab_b = st.optDouble("b", -1);
            String abState = ab_a >= 0 && ab_b > ab_a ? "正在循环 " + fmtTime(ab_a) + " – " + fmtTime(ab_b)
                    : ab_a >= 0 ? "A 点 " + fmtTime(ab_a) + "，再设 B 点" : "先在起点设 A，再在终点设 B";
            box.addView(row("A-B 循环", abState, null));
            LinearLayout abRow = new LinearLayout(a);
            abRow.setOrientation(LinearLayout.HORIZONTAL);
            abRow.setPadding(dp(12), 0, dp(12), dp(4));
            abRow.addView(chip("设 A 点", v -> { MainActivity.js("ytl.ab('a')"); refresh(); }), weight());
            abRow.addView(chip("设 B 点", v -> { MainActivity.js("ytl.ab('b')"); refresh(); }), weight());
            abRow.addView(chip("清除", v -> { MainActivity.js("ytl.ab('clear')"); refresh(); }), weight());
            box.addView(abRow);

            if (st.optBoolean("list", false) && st.optBoolean("shuffle", false)) {
                LinearLayout sh = new LinearLayout(a);
                sh.setOrientation(LinearLayout.HORIZONTAL);
                sh.setPadding(dp(12), dp(4), dp(12), dp(4));
                sh.addView(chip("列表随机播放", v -> { MainActivity.js("ytl.shuffle(true)"); toast("已开启随机播放"); }), weight());
                sh.addView(chip("顺序播放", v -> { MainActivity.js("ytl.shuffle(false)"); toast("已恢复顺序播放"); }), weight());
                box.addView(sh);
            }
        }

        box.addView(header("屏幕方向"));
        int lock = a.orientationLock();
        LinearLayout or = new LinearLayout(a);
        or.setOrientation(LinearLayout.HORIZONTAL);
        or.setPadding(dp(12), dp(4), dp(12), dp(4));
        or.addView(chip(mark("跟随系统", lock == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
                v -> { a.setOrientationLock(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED); refresh(); }), weight());
        or.addView(chip(mark("锁定竖屏", lock == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
                v -> { a.setOrientationLock(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); refresh(); }), weight());
        or.addView(chip(mark("锁定横屏", lock == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
                v -> { a.setOrientationLock(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE); refresh(); }), weight());
        box.addView(or);
        // remembered across starts (388)
        final boolean remember = Prefs.on(a, Prefs.ORIENT_REMEMBER);
        box.addView(row(remember ? "记住屏幕方向：开 ✓" : "记住屏幕方向：关", "下次打开 App 沿用这里选的方向", v -> {
            a.setOrientRemember(!remember);
            refresh();
        }));
        return box;
    }

    // ---- -10 / +30 s (342): presses within 1.5 s add up, the chip shows the total for a moment ----
    private int seekSum = 0;
    private Button seekShown;
    private final Runnable seekReset = () -> {
        seekSum = 0;
        if (seekShown != null) { seekShown.setText((String) seekShown.getTag()); seekShown = null; }
    };

    private Button seekChip(String label, final int sec) {
        final Button b = chip(label, null);
        b.setTag(label);
        b.setOnClickListener(v -> {
            MainActivity.js("ytl.seekBy(" + sec + ")");
            if (seekShown != null && seekShown != b) seekReset.run();
            seekSum += sec;
            seekShown = b;
            b.setText(seekSum < 0 ? "−" + (-seekSum) + " 秒" : "+" + seekSum + " 秒");
            MainActivity.MAIN_HANDLER.removeCallbacks(seekReset);
            MainActivity.MAIN_HANDLER.postDelayed(seekReset, 1500);
        });
        return b;
    }

    private void dismiss() {
        try { if (dialog != null) dialog.dismiss(); } catch (Exception ignored) {}
    }

    // ===================== sub dialogs =====================

    public interface IntPick { void pick(int value); }

    /** Sleep timer options (shared with the settings screen). */
    public static void pickSleep(MainActivity a, String title, IntPick cb) {
        // native bottom sheet (165)
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView head = Ui.text(a, title, 18, Ui.TEXT);
        head.setPadding(Ui.dp(a, 20), Ui.dp(a, 4), Ui.dp(a, 20), Ui.dp(a, 8));
        box.addView(head);
        final Dialog[] d = new Dialog[1];
        int def = Prefs.sleepDefault(a);
        // running timer (304): what is left, and switching it off, right here
        boolean running = SleepTimer.active() && "睡眠定时".equals(title);
        if (running) {
            box.addView(Ui.sheetRow(a, R.drawable.ic_close_white, "关闭睡眠定时", SleepTimer.status(), v -> { d[0].dismiss(); SleepTimer.cancel(a); }));
        }
        for (final int m : SleepTimer.OPTIONS) {
            boolean cur = running && SleepTimer.mode() == m;
            String sub = cur ? "正在使用" + (m == def ? " · 默认" : "") : m == def ? "默认" : null;
            box.addView(Ui.sheetRow(a, SleepTimer.optionLabel(m) + (cur ? " ✓" : ""), sub, v -> { d[0].dismiss(); cb.pick(m); }));
        }
        d[0] = Ui.sheet(a, box);
        d[0].show();
    }

    private void pickSpeed(double current) {
        final int min = 5, max = 40; // x 0.05
        int cur = (int) Math.round(Math.max(0.25, Math.min(2, current)) / 0.05);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        final TextView label = text(fmtRate(cur * 0.05), 22, Ui.TEXT);
        label.setGravity(Gravity.CENTER);
        box.addView(label);
        final SeekBar bar = new SeekBar(a);
        bar.setMax(max - min);
        bar.setProgress(cur - min);
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(chip("−0.05", v -> bar.setProgress(Math.max(0, bar.getProgress() - 1))), weight());
        btns.addView(chip("1.0x", v -> bar.setProgress(20 - min)), weight());
        btns.addView(chip("+0.05", v -> bar.setProgress(Math.min(max - min, bar.getProgress() + 1))), weight());
        box.addView(btns);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                double r = (p + min) * 0.05;
                label.setText(fmtRate(r));
                MainActivity.js("ytl.setRate(" + String.format(java.util.Locale.US, "%.2f", r) + ")");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("播放速度")
                .setView(box)
                .setPositiveButton("完成", null)
                .show();
    }

    // ===================== views =====================

    private static String mark(String s, boolean on) { return on ? "✓ " + s : s; }

    private static String fmtRate(double r) {
        return String.format(java.util.Locale.US, "%.2fx", r);
    }

    private static String fmtTime(double sec) {
        int s = (int) Math.max(0, Math.floor(sec));
        int h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, ss)
                : String.format(java.util.Locale.US, "%d:%02d", m, ss);
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        return lp;
    }

    private Button chip(String label, View.OnClickListener click) {
        Button b = new Button(a, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Ui.TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackground(Ui.round(Ui.CHIP, dp(20)));
        b.setMinHeight(dp(48));
        b.setOnClickListener(click);
        return b;
    }

    private View row(String title, String sub, View.OnClickListener click) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(10), dp(20), dp(10));
        l.setMinimumHeight(dp(48));
        l.addView(text(title, 16, Ui.TEXT));
        if (sub != null && !sub.isEmpty()) l.addView(text(sub, 12, Ui.TEXT_2));
        if (click != null) {
            TypedValue tv = new TypedValue();
            a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            l.setBackgroundResource(tv.resourceId);
            l.setOnClickListener(click);
        }
        return l;
    }

    private TextView header(String label) {
        TextView t = text(label, 13, Ui.accent(a));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(20), dp(12), dp(20), dp(2));
        return t;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private void toast(String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
