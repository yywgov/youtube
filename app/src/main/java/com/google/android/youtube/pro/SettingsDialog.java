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
        box.addView(toggle("后台/熄屏时最低画质", "省电省流量；回到前台恢复默认画质", Prefs.LOW_QUALITY_BG, Prefs.lowQualityBg(a)));
        box.addView(choice("默认画质", "每个视频开始时设一次，之后可手动改", Prefs.QUALITY,
                new String[]{"自动（YouTube 决定）", "Wi-Fi 最高，流量 720p", "1080p", "720p", "480p"},
                new String[]{"auto", "wifiMax", "1080", "720", "480"}, "auto"));
        box.addView(toggle("记住播放速度", "下一个视频沿用上次选的速度（最高 2 倍）", Prefs.REMEMBER_RATE, Prefs.on(a, Prefs.REMEMBER_RATE)));
        box.addView(toggle("优先原声音轨", "有多条音轨（AI 自动配音）时切回原声", Prefs.ORIG_AUDIO, Prefs.on(a, Prefs.ORIG_AUDIO)));
        box.addView(toggle("视频结束不显示推荐墙", "只隐藏片尾推荐网格，自动续播倒计时照常", Prefs.NO_END_WALL, Prefs.on(a, Prefs.NO_END_WALL)));
        box.addView(toggle("音量均衡（实验）", "压缩忽大忽小的音量；若关屏后没声音请关闭", Prefs.NORMALIZE, Prefs.on(a, Prefs.NORMALIZE)));

        box.addView(header("字幕"));
        box.addView(choice("默认字幕", "跟随 YouTube 时不做任何改动", Prefs.CAPTIONS,
                new String[]{"跟随 YouTube", "默认关闭", "有中文字幕时打开"}, new String[]{"yt", "off", "zh"}, "yt"));
        box.addView(choice("字幕大小", null, Prefs.SUB_SIZE,
                new String[]{"默认", "小", "大", "特大"}, new String[]{"default", "s", "l", "xl"}, "default"));
        box.addView(choice("字幕背景", null, Prefs.SUB_BG,
                new String[]{"默认", "半透明", "无背景（文字描边）"}, new String[]{"default", "half", "none"}, "default"));

        box.addView(header("续播"));
        box.addView(choice("接下来播放", "默认用 YouTube 自动播放；其他选项会关掉它由本 App 选", Prefs.NEXT_SOURCE,
                new String[]{"YouTube 推荐（默认）", "优先同频道", "只在播放列表内"}, new String[]{"rec", "channel", "playlist"}, "rec"));
        box.addView(toggle("睡眠定时期间只续播同频道/列表", "睡着时不跳到刺激内容；找不到同频道视频时照常推荐", Prefs.SLEEP_CALM, Prefs.on(a, Prefs.SLEEP_CALM)));
        box.addView(toggle("未登录时记住观看进度", "只存在本机，再打开同一视频从上次位置继续", Prefs.RESUME_LOCAL, Prefs.on(a, Prefs.RESUME_LOCAL)));
        box.addView(toggle("重开时提示继续上次视频", "被系统清掉后打开 App，底部提示一键回到断点", Prefs.RESUME_PROMPT, Prefs.on(a, Prefs.RESUME_PROMPT)));

        box.addView(header("首页"));
        box.addView(toggle("首页隐藏已看完的视频", "看过 80% 以上的不再占位置，刷新更容易出新视频", Prefs.HIDE_WATCHED, Prefs.hideWatched(a)));
        box.addView(toggle("列表渲染优化", "屏幕外的卡片不渲染、去掉骨架闪烁和点按涟漪；排版异常时可关闭", Prefs.PERF_CSS, Prefs.perfCss(a)));

        box.addView(header("睡眠定时"));
        sleepNowRow = row("", v -> {
            if (SleepTimer.active()) {
                SleepTimer.cancel(a);
                refresh();
            } else {
                PlayerTools.pickSleep(a, "现在开始睡眠定时", m -> { SleepTimer.start(a, m); refresh(); });
            }
        });
        box.addView(sleepNowRow);
        sleepDefaultRow = row("", v -> PlayerTools.pickSleep(a, "默认时长（通知栏按钮）", m -> {
            Prefs.put(a, Prefs.SLEEP_DEFAULT, m);
            refresh();
        }));
        box.addView(sleepDefaultRow);
        box.addView(choiceInt("淡出时长", "到点前音量逐渐变小的时间", Prefs.SLEEP_FADE,
                new String[]{"10 秒", "30 秒", "60 秒"}, new int[]{10, 30, 60}, 60));
        box.addView(toggle("开启的时长记为默认", "下次在通知栏一键开启同样的时长", Prefs.SLEEP_REMEMBER, Prefs.on(a, Prefs.SLEEP_REMEMBER)));
        box.addView(toggle("播放器角上显示剩余时间", null, Prefs.SLEEP_BADGE, Prefs.on(a, Prefs.SLEEP_BADGE)));
        box.addView(toggle("夜间打开时提示开定时", "22:00–5:00 打开 App 时提示一次，每晚最多一次", Prefs.NIGHT_PROMPT, Prefs.on(a, Prefs.NIGHT_PROMPT)));

        box.addView(header("通知栏与耳机"));
        box.addView(toggle("通知栏用快退/快进", "把「上一个/下一个」换成「快退 10 秒/快进 30 秒」", Prefs.NOTIF_SEEK, Prefs.on(a, Prefs.NOTIF_SEEK)));
        box.addView(toggle("耳机按键多击", "按一下暂停/播放，两下下一个，三下上一个", Prefs.HEADSET_MULTI, Prefs.on(a, Prefs.HEADSET_MULTI)));

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

    private static void applied(MainActivity a) {
        MainActivity.js("ytl.cfg(" + Prefs.jsConfig(a) + ")");
        PlaybackService.refresh();
    }

    /** Row with the current choice as subtitle; picking a value saves and applies it at once. */
    private View choice(final String title, final String note, final String key, final String[] labels, final String[] values, final String def) {
        final LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(10), dp(16), dp(10));
        l.addView(text(title, 16, 0xFFFFFFFF));
        final TextView sub = text("", 12, 0xFFAAAAAA);
        l.addView(sub);
        final Runnable show = () -> {
            String cur = Prefs.str(a, key, def);
            String label = labels[0];
            for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) label = labels[i];
            sub.setText(note == null ? label : label + " · " + note);
        };
        show.run();
        TypedValue tv = new TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        l.setBackgroundResource(tv.resourceId);
        l.setOnClickListener(v -> {
            String cur = Prefs.str(a, key, def);
            int sel = 0;
            for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) sel = i;
            new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, sel, (d, which) -> {
                        Prefs.put(a, key, values[which]);
                        applied(a);
                        show.run();
                        d.dismiss();
                    })
                    .show();
        });
        return l;
    }

    private View choiceInt(String title, String note, final String key, String[] labels, int[] values, int def) {
        final LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(10), dp(16), dp(10));
        l.addView(text(title, 16, 0xFFFFFFFF));
        final TextView sub = text("", 12, 0xFFAAAAAA);
        l.addView(sub);
        final Runnable show = () -> {
            int cur = Prefs.get(a).getInt(key, def);
            String label = String.valueOf(cur);
            for (int i = 0; i < values.length; i++) if (values[i] == cur) label = labels[i];
            sub.setText(note == null ? label : label + " · " + note);
        };
        show.run();
        TypedValue tv = new TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        l.setBackgroundResource(tv.resourceId);
        l.setOnClickListener(v -> {
            int cur = Prefs.get(a).getInt(key, def);
            int sel = 0;
            for (int i = 0; i < values.length; i++) if (values[i] == cur) sel = i;
            new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, sel, (d, which) -> {
                        Prefs.put(a, key, values[which]);
                        applied(a);
                        show.run();
                        d.dismiss();
                    })
                    .show();
        });
        return l;
    }

    private View toggle(String title, String sub, final String key, boolean value) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(16), dp(10), dp(12), dp(10));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 16, 0xFFFFFFFF));
        if (sub != null) texts.addView(text(sub, 12, 0xFFAAAAAA));
        l.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        final Switch sw = new Switch(a);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, checked) -> {
            Prefs.put(a, key, checked);
            applied(a);
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
