package com.google.android.youtube.pro;

import android.app.Dialog;
import android.content.pm.ActivityInfo;
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

/**
 * Native bottom sheet with the playback tools (moon button next to the settings gear):
 * sleep timer, black screen (157), touch lock (131), orientation lock (132),
 * fine speed (151), single-video / A-B loop (152), playlist shuffle (153), -10 / +30 s.
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

    /** Toggles stay in the sheet (335): the page state is read again and the rows are redrawn in place. */
    private void refresh() {
        MainActivity.MAIN_HANDLER.postDelayed(() -> a.toolState(s -> {
            if (dialog == null || !dialog.isShowing()) return;
            st = s == null ? new JSONObject() : s;
            holder.removeAllViews();
            holder.addView(content());
        }), 120);
    }

    /** The same bottom-sheet frame as every other sheet (352): handle, corners, dim, width on tablets. */
    private void build() {
        holder = new LinearLayout(a);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.addView(content());
        dialog = Ui.sheet(a, holder);
        dialog.show();
    }

    private LinearLayout content() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, dp(4));

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
            seekRow.addView(seekChip("快退 10 秒", -10), Ui.chipWeight(a));
            seekRow.addView(seekChip("快进 30 秒", 30), Ui.chipWeight(a));
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
            abRow.addView(Ui.chip(a, "设 A 点", v -> { MainActivity.js("ytl.ab('a')"); refresh(); }), Ui.chipWeight(a));
            abRow.addView(Ui.chip(a, "设 B 点", v -> { MainActivity.js("ytl.ab('b')"); refresh(); }), Ui.chipWeight(a));
            abRow.addView(Ui.chip(a, "清除", v -> { MainActivity.js("ytl.ab('clear')"); refresh(); }), Ui.chipWeight(a));
            box.addView(abRow);

            if (st.optBoolean("list", false) && st.optBoolean("shuffle", false)) {
                LinearLayout sh = new LinearLayout(a);
                sh.setOrientation(LinearLayout.HORIZONTAL);
                sh.setPadding(dp(12), dp(4), dp(12), dp(4));
                sh.addView(Ui.chip(a, "列表随机播放", v -> { MainActivity.js("ytl.shuffle(true)"); toast("已开启随机播放"); }), Ui.chipWeight(a));
                sh.addView(Ui.chip(a, "顺序播放", v -> { MainActivity.js("ytl.shuffle(false)"); toast("已恢复顺序播放"); }), Ui.chipWeight(a));
                box.addView(sh);
            }
        }

        box.addView(header("屏幕方向"));
        int lock = a.orientationLock();
        LinearLayout or = new LinearLayout(a);
        or.setOrientation(LinearLayout.HORIZONTAL);
        or.setPadding(dp(12), dp(4), dp(12), dp(4));
        or.addView(orient("跟随系统", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, lock), Ui.chipWeight(a));
        or.addView(orient("锁定竖屏", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, lock), Ui.chipWeight(a));
        or.addView(orient("锁定横屏", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, lock), Ui.chipWeight(a));
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

    /** Orientation chip: the current one carries a check icon (354). */
    private Button orient(String label, final int o, int cur) {
        Button b = Ui.chip(a, label, v -> { a.setOrientationLock(o); refresh(); });
        Ui.markChip(b, o == cur);
        if (o == cur) b.setContentDescription(label + "，当前");
        return b;
    }

    private Button seekChip(String label, final int sec) {
        final Button b = Ui.chip(a, label, null);
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
            box.addView(Ui.sheetRow(a, cur ? R.drawable.ic_check : 0, SleepTimer.optionLabel(m), sub, v -> { d[0].dismiss(); cb.pick(m); }));
        }
        d[0] = Ui.sheet(a, box);
        d[0].show();
    }

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
        TextView head = Ui.text(a, "播放速度", 18, Ui.TEXT);
        head.setPadding(dp(8), dp(4), dp(8), dp(4));
        Ui.heading(head);
        box.addView(head);
        final TextView label = text(fmtRate(cur * 0.05), 22, Ui.TEXT);
        label.setGravity(Gravity.CENTER);
        box.addView(label);
        final SeekBar bar = new SeekBar(a);
        bar.setMax(max - min);
        bar.setProgress(cur - min);
        bar.setContentDescription("播放速度");
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

    private static String fmtTime(double sec) {
        int s = (int) Math.max(0, Math.floor(sec));
        int h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, ss)
                : String.format(java.util.Locale.US, "%d:%02d", m, ss);
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
        return Ui.header(a, label);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private void toast(String s) {
        a.say(s);
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
