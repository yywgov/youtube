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
    private TextView sbCatsRow;
    private TextView adsLogRow;
    private TextView diagLogRow;

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
        box.addView(toggle("SponsorBlock 自动跳过", "跳过后 2 秒内可点「撤销」回看", Prefs.SPONSOR_BLOCK, Prefs.sponsorBlock(a)));
        sbCatsRow = row("", v -> pickSponsorCats());
        box.addView(sbCatsRow);
        box.addView(toggle("后台/熄屏时最低画质", "省电省流量；回到前台恢复自动画质", Prefs.LOW_QUALITY_BG, Prefs.lowQualityBg(a)));
        box.addView(toggle("首页隐藏已看完的视频", "看过 80% 以上的不再占位置，刷新更容易出新视频", Prefs.HIDE_WATCHED, Prefs.hideWatched(a)));

        box.addView(toggle("列表渲染优化", "屏幕外的卡片不渲染、去掉骨架闪烁和点按涟漪；排版异常时可关闭", Prefs.PERF_CSS, Prefs.perfCss(a)));

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

        box.addView(header("诊断（只存在本机）"));
        adsLogRow = row("", v -> copyLog(Diag.ADS, "漏网广告记录"));
        box.addView(adsLogRow);
        diagLogRow = row("", v -> copyLog(Diag.DIAG, "诊断日志"));
        box.addView(diagLogRow);
        box.addView(row("清空以上记录", v -> {
            Diag.clear(a, Diag.ADS);
            Diag.clear(a, Diag.DIAG);
            refresh();
            android.widget.Toast.makeText(a, "已清空", android.widget.Toast.LENGTH_SHORT).show();
        }));

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
        sbCatsRow.setText("跳过类别：" + Prefs.sponsorCatsLabel(a));
        adsLogRow.setText("复制漏网广告记录（" + Diag.lines(a, Diag.ADS) + " 条）");
        diagLogRow.setText("复制诊断日志（" + Diag.lines(a, Diag.DIAG) + " 行）");
        batteryRow.setText(a.isIgnoringBattery() ? "电池优化：已不受限制 ✓" : "关闭电池优化（熄屏持续播放必需）");
    }

    private void pickSponsorCats() {
        final String[] keys = Prefs.SB_CATS;
        final java.util.List<String> cur = Prefs.sponsorCats(a);
        final boolean[] checked = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) checked[i] = cur.contains(keys[i]);
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("SponsorBlock 跳过类别")
                .setMultiChoiceItems(Prefs.SB_LABELS, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("确定", (d, w) -> {
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < keys.length; i++) {
                        if (!checked[i]) continue;
                        if (b.length() > 0) b.append(',');
                        b.append(keys[i]);
                    }
                    Prefs.put(a, Prefs.SPONSOR_CATS, b.toString());
                    refresh();
                    android.widget.Toast.makeText(a, "从下一个视频开始生效", android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyLog(String file, String label) {
        String body = Diag.read(a, file);
        if (body.isEmpty()) {
            android.widget.Toast.makeText(a, label + "为空", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("YT Lite " + label, Diag.header(version()) + body));
            android.widget.Toast.makeText(a, "已复制" + label, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(a, "复制失败", android.widget.Toast.LENGTH_SHORT).show();
        }
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
