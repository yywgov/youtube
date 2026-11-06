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
            box.addView(row(loop ? "单曲循环：开 ✓" : "单曲循环：关", "反复播放当前视频", v -> {
                MainActivity.js("ytl.loop(" + !loop + ")");
                refresh();
            }));
        }
        box.addView(header("屏幕"));
        box.addView(row("黑屏播放", "屏幕全黑不锁屏，OLED 几乎不耗电；双击恢复", v -> { dismiss(); a.startBlackScreen(); }));
        box.addView(row("触控锁", "防误触；长按屏幕 1.5 秒解锁", v -> { dismiss(); a.startTouchLock(); }));
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

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
