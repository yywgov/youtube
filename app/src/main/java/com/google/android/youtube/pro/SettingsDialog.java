package com.google.android.youtube.pro;

import android.app.AlertDialog;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Native settings screen with only the options YT Lite still has. */
public class SettingsDialog {
    private final MainActivity a;
    private AlertDialog dialog;
    private TextView sleepDefaultRow;
    private TextView sleepNowRow;
    private TextView batteryRow;

    public SettingsDialog(MainActivity activity) {
        this.a = activity;
    }

    public void show() {
        ScrollView scroll = new ScrollView(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        box.setPadding(pad, pad, pad, pad);
        scroll.addView(box);

        box.addView(header("播放与内容"));
        box.addView(toggle("SponsorBlock 自动跳过", "自动跳过赞助、自我推广和求订阅片段", Prefs.SPONSOR_BLOCK, Prefs.sponsorBlock(a)));
        box.addView(toggle("后台/熄屏时最低画质", "省电省流量；回到前台恢复自动画质", Prefs.LOW_QUALITY_BG, Prefs.lowQualityBg(a)));
        box.addView(toggle("首页隐藏已看完的视频", "看过 80% 以上的不再占位置，刷新更容易出新视频", Prefs.HIDE_WATCHED, Prefs.hideWatched(a)));

        box.addView(header("睡眠定时"));
        sleepNowRow = row("", v -> {
            if (SleepTimer.active()) {
                SleepTimer.cancel(a);
                refresh();
            } else {
                pickSleep("现在开始睡眠定时", m -> { SleepTimer.start(a, m); refresh(); });
            }
        });
        box.addView(sleepNowRow);
        sleepDefaultRow = row("", v -> pickSleep("默认时长（通知栏按钮）", m -> {
            Prefs.put(a, Prefs.SLEEP_DEFAULT, m);
            refresh();
        }));
        box.addView(sleepDefaultRow);

        box.addView(header("后台播放"));
        batteryRow = row("", v -> a.requestIgnoreBattery());
        box.addView(batteryRow);
        box.addView(row("应用详情（自启动 / 耗电策略）", v -> a.openAppDetails()));

        box.addView(header("关于"));
        box.addView(row("检查更新", v -> Updater.checkNow(a)));
        TextView ver = text("YT Lite " + version() + " · 每天自动检查一次更新 · 基于 YTPro (MIT)", 12, 0xFF9E9E9E);
        ver.setPadding(dp(16), dp(8), dp(16), dp(4));
        box.addView(ver);

        refresh();
        dialog = new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("YT Lite 设置")
                .setView(scroll)
                .setPositiveButton("完成", null)
                .create();
        dialog.setOnShowListener(d -> refresh());
        dialog.show();
    }

    private void refresh() {
        sleepNowRow.setText(SleepTimer.active()
                ? "关闭睡眠定时（" + SleepTimer.optionLabel(SleepTimer.mode()) + "）"
                : "现在开始睡眠定时…");
        sleepDefaultRow.setText("通知栏按钮默认时长：" + SleepTimer.optionLabel(Prefs.sleepDefault(a)));
        batteryRow.setText(a.isIgnoringBattery() ? "电池优化：已不受限制 ✓" : "关闭电池优化（熄屏持续播放必需）");
    }

    private interface IntPick { void pick(int minutes); }

    private void pickSleep(String title, IntPick cb) {
        int[] opts = SleepTimer.OPTIONS;
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) labels[i] = SleepTimer.optionLabel(opts[i]);
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setItems(labels, (d, which) -> cb.pick(opts[which]))
                .show();
    }

    private View toggle(String title, String sub, final String key, boolean value) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(16), dp(10), dp(12), dp(10));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 16, 0xFFFFFFFF));
        texts.addView(text(sub, 12, 0xFFAAAAAA));
        l.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        final Switch sw = new Switch(a);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, checked) -> {
            Prefs.put(a, key, checked);
            MainActivity.js("ytl.cfg(" + Prefs.jsConfig(a) + ")");
        });
        l.addView(sw);
        l.setOnClickListener(v -> sw.toggle());
        return l;
    }

    private TextView row(String label, View.OnClickListener click) {
        TextView t = text(label, 16, 0xFFFFFFFF);
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        TypedValue tv = new TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        t.setBackgroundResource(tv.resourceId);
        t.setOnClickListener(click);
        return t;
    }

    private TextView header(String label) {
        TextView t = text(label, 13, 0xFFFF4E45);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(16), dp(14), dp(16), dp(2));
        return t;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private String version() {
        try {
            PackageInfo i = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return i.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
