package com.google.android.youtube.pro;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Native full-screen settings (101), Material 3 style: a main page of categories, each opening
 * its own page of grouped cards (90). Every row carries one line of explanation (134) and takes
 * effect at once (135). Drawn as full-screen dialogs on top of the player so playback, the
 * background quality and the page script keep running exactly as before.
 */
public class SettingsPage {
    private final MainActivity a;
    private final List<Runnable> refreshers = new ArrayList<>();

    public SettingsPage(MainActivity a) {
        this.a = a;
    }

    /** Undo for "restore defaults" / import (406): offered on the root page for 10 s. */
    private static long undoUntil = 0;
    private static String undoWhat = "";
    private int hiddenCount = -1;

    public void show() {
        page(a.getString(R.string.app_name) + " 设置", box -> {
            statusCard(box);
            LinearLayout q = card(box);
            q.addView(row(SEARCH_TITLE, "输入关键字，直接跳到那一项（如：字幕、定时、屏蔽）", v -> searchPage()));   // (442)
            LinearLayout c = card(box);
            // a line icon per group (359), same family as the app icon
            c.addView(nav(R.drawable.ic_set_play, "播放", "画质、声音与速度、续播提示、字幕、SponsorBlock", this::playPage));
            c.addView(nav(R.drawable.ic_moon_white, "后台与睡眠", "保活、睡眠定时、接下来播放、通知栏与耳机", this::sleepPage));
            c.addView(nav(R.drawable.ic_set_home, "首页与内容", "启动页、已看完、内容过滤、隐藏模块、搜索", this::listPage));
            c.addView(nav(R.drawable.ic_set_palette, "外观与交互", "主题、字号、轻震、列表样式、滑动流畅度", this::lookPage));
            c.addView(nav(R.drawable.ic_set_cloud, "数据与更新", "更新、备份与恢复、关于与诊断", this::dataPage));
        });
    }

    /** Root status card (225/291/292): only shown when something needs attention. */
    private void statusCard(LinearLayout box) {
        List<View> rows = new ArrayList<>();
        if (System.currentTimeMillis() < undoUntil) {
            final View[] self = new View[1];
            self[0] = row("撤销刚才的「" + undoWhat + "」", "10 秒内可撤销，回到之前的设置", v -> {
                undoUntil = 0;
                if (Prefs.undoSnapshot(a)) {
                    a.applySettings();
                    toastShort("已撤销");
                    reopen();
                } else toastShort("没有可撤销的内容");
            });
            rows.add(self[0]);
            final long left = Math.max(0, undoUntil - System.currentTimeMillis());
            self[0].postDelayed(() -> self[0].setVisibility(View.GONE), left);
        }
        String ready = Updater.readyTag(a);
        if (ready != null) rows.add(row("发现新版本 " + ready, "点此下载并安装（不会自动弹窗打扰）", v -> Updater.checkNow(a)));
        if (!a.notificationsOk()) rows.add(row("通知已关闭", "锁屏和通知栏无法控制播放 · 点此打开通知设置", v -> a.openNotificationSettings()));
        if (!a.isIgnoringBattery()) rows.add(row("后台保活未完成", "电池优化仍在限制本 App，息屏播放可能中断 · 点此设置", v -> keepAliveSheet()));
        if (a.fgsDeniedAt() > 0) rows.add(row("上次后台播放没能保持前台", "系统拒绝了后台播放服务 · 点此检查保活设置", v -> { a.clearFgsDenied(); keepAliveSheet(); }));
        if (rows.isEmpty()) return;
        LinearLayout card = card(box);
        for (View r : rows) card.addView(r);
    }

    private void reopen() {
        closeAll();
        new SettingsPage(a).show();
    }

    // ===================== 1. 播放 =====================

    private static final String[] Q_LABELS = {"自动（YouTube 决定）", "Wi-Fi 最高，流量 720p", "1080p", "720p", "480p"};
    private static final String[] Q_VALUES = {"auto", "wifiMax", "1080", "720", "480"};
    private static final String[] Q_SHORT = {"自动", "Wi-Fi 最高", "1080p", "720p", "480p"};

    private void playPage() {
        page("播放", box -> {
            LinearLayout q = card(box, "画质");
            q.addView(sub("画质策略", () -> "前台 " + pick(Q_VALUES, Q_SHORT, Prefs.str(a, Prefs.QUALITY, "auto"))
                    + " · 后台 " + (Prefs.on(a, Prefs.LOW_QUALITY_BG) ? "最低" : "不变")
                    + " · 省电 " + (Prefs.on(a, Prefs.POWER_SAVE_AUTO) ? "≤480p" : "不变"), this::qualityPage));
            LinearLayout s = card(box, "声音、速度与续播");
            s.addView(toggle("优先原声音轨", "有多条音轨（AI 自动配音）时切回原声", Prefs.ORIG_AUDIO));
            s.addView(toggle("记住播放速度", "下一个视频沿用上次选的速度（最高 2 倍）", Prefs.REMEMBER_RATE));
            // "remember progress when signed out" lost its row (461): signed in anyway; resumeLocal keeps its stored value
            s.addView(toggle("重开时提示继续上次视频", "被系统清掉后打开 App，底部提示一键回到断点", Prefs.RESUME_PROMPT));
            LinearLayout c = card(box, "字幕与片段");
            c.addView(sub("字幕", () -> pick(CAP_VALUES, CAP_SHORT, Prefs.str(a, Prefs.CAPTIONS, "yt"))
                    + " · 大小" + pick(new String[]{"default", "s", "l", "xl"}, new String[]{"默认", "小", "大", "特大"}, Prefs.str(a, Prefs.SUB_SIZE, "default"))
                    + " · 背景" + pick(new String[]{"default", "half", "none"}, new String[]{"默认", "半透明", "无"}, Prefs.str(a, Prefs.SUB_BG, "default")), this::captionsPage));
            c.addView(dyn("SponsorBlock", this::sponsorLabel, v -> pickSponsor()));
            c.addView(toggle("视频结束不显示推荐墙", "只隐藏片尾推荐网格，自动续播倒计时照常", Prefs.NO_END_WALL));
        });
    }

    private void qualityPage() {
        page("画质策略", box -> {
            LinearLayout c = card(box);
            c.addView(choice("默认画质", "每个视频开始时设一次，之后可手动改", Prefs.QUALITY, Q_LABELS, Q_VALUES, "auto"));
            c.addView(toggle("后台/息屏时最低画质", "省电省流量；回到前台恢复默认画质", Prefs.LOW_QUALITY_BG));
            footnote(box, "同时生效时的优先级：一键睡眠模式 > 后台/息屏最低 > 省电上限 480p > 默认画质。");   // (441)
        });
    }

    // captions (341): Chinese (any), simplified / traditional first, English; human captions before auto ones
    private static final String[] CAP_VALUES = {"yt", "off", "zh", "zhHans", "zhHant", "en"};
    private static final String[] CAP_SHORT = {"跟随 YouTube", "默认关闭", "有中文时打开", "简体优先", "繁体优先", "有英文时打开"};
    private static final String[] CAP_LABELS = {"跟随 YouTube", "默认关闭", "有中文字幕时打开", "有简体中文时打开（无则其他中文）",
            "有繁体中文时打开（无则其他中文）", "有英文字幕时打开"};

    private void captionsPage() {
        page("字幕", box -> {
            LinearLayout s = card(box);
            s.addView(choice("默认字幕", "跟随 YouTube 时不做任何改动；人工字幕优先于自动字幕", Prefs.CAPTIONS, CAP_LABELS, CAP_VALUES, "yt"));
            s.addView(choice("字幕大小", "只改显示大小，不影响字幕内容", Prefs.SUB_SIZE,
                    new String[]{"默认", "小", "大", "特大"}, new String[]{"default", "s", "l", "xl"}, "default"));
        });
    }

    private String sponsorLabel() {
        if (!Prefs.sponsorBlock(a)) return "关闭";
        if (Prefs.sponsorCats(a).isEmpty()) return "已开启但未选类别（不会跳过）";
        return "跳过：" + Prefs.sponsorCatsLabel(a) + " · 跳过后 2 秒内可撤销";
    }

    /** One sheet for SponsorBlock (230): master switch on top, categories below; keys stay separate. */
    private void pickSponsor() {
        final String[] keys = Prefs.SB_CATS;
        final List<String> cur = Prefs.sponsorCats(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), 0);
        final Switch master = new Switch(a);
        master.setText("自动跳过片段（总开关）");
        master.setTextColor(Ui.TEXT);
        master.setTextSize(16);
        master.setChecked(Prefs.sponsorBlock(a));
        master.setPadding(dp(16), dp(12), dp(16), dp(12));
        tint(master);
        box.addView(master);
        final android.widget.CheckBox[] boxes = new android.widget.CheckBox[keys.length];
        for (int i = 0; i < keys.length; i++) {
            android.widget.CheckBox cb = new android.widget.CheckBox(a);
            cb.setText(Prefs.SB_LABELS[i]);
            cb.setTextColor(Ui.TEXT);
            cb.setChecked(cur.contains(keys[i]));
            cb.setPadding(dp(8), dp(6), dp(8), dp(6));
            cb.setEnabled(master.isChecked());
            boxes[i] = cb;
            box.addView(cb);
        }
        master.setOnCheckedChangeListener((b, on) -> { for (android.widget.CheckBox cb : boxes) cb.setEnabled(on); });
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("SponsorBlock")
                .setView(sv)
                .setPositiveButton("确定", (d, w) -> {
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < keys.length; i++) {
                        if (!boxes[i].isChecked()) continue;
                        if (b.length() > 0) b.append(',');
                        b.append(keys[i]);
                    }
                    Prefs.put(a, Prefs.SPONSOR_BLOCK, master.isChecked());
                    Prefs.put(a, Prefs.SPONSOR_CATS, b.toString());
                    applied();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===================== 2. 后台与睡眠 =====================

    private void sleepPage() {
        page("后台与睡眠", box -> {
            LinearLayout k = card(box, "后台保活（息屏续播必需）");
            k.addView(dyn("保活状态", this::keepAliveLabel, v -> keepAliveSheet()));
            LinearLayout c = card(box, "睡眠定时");
            c.addView(dyn("默认时长", this::sleepDefaultLabel, v -> pickSleepDefault()));
            // fade, extend step, pause-holds-timer, corner badge and one-tap black screen share one row (474)
            c.addView(dyn("定时细节", this::sleepDetailLabel, v -> pickSleepDetails()));
            // night window + night prompt in one row (456)
            c.addView(dyn("夜间打开时提示开睡眠定时", this::nightPromptLabel, v -> pickNightPrompt()));
            c.addView(toggle("无操作保护", "连续 3 小时没碰手机、没按耳机键又没开定时，自动开 30 分钟睡眠定时", Prefs.IDLE_GUARD));
            LinearLayout n = card(box, "续播");
            n.addView(dyn("接下来播放", this::nextLabel, v -> pickNext()));
            LinearLayout h = card(box, "通知栏与耳机");
            // the headset multi-click switch moved into this row's sheet (475)
            h.addView(dyn("通知栏按钮与耳机", () -> PrefsModel.notifLayout(Prefs.on(a, Prefs.NOTIF_SEEK), Prefs.on(a, Prefs.NOTIF_WL))
                    + (Prefs.on(a, Prefs.HEADSET_MULTI) ? " · 耳机多击" : " · 耳机多击已关"), v -> pickNotif()));
        });
    }

    private String sleepDetailLabel() {
        return PrefsModel.sleepDetailLabel(Prefs.get(a).getInt(Prefs.SLEEP_FADE, 60), Prefs.get(a).getInt(Prefs.EXTEND_STEP, 15),
                Prefs.on(a, Prefs.SLEEP_HOLD), Prefs.on(a, Prefs.SLEEP_BADGE), Prefs.on(a, Prefs.SLEEP_MODE_BLACK));
    }

    private static final int[] FADE_VALUES = {10, 30, 60};
    private static final int[] STEP_VALUES = {10, 15, 30};

    private static int indexOf(int[] v, int x, int def) {
        for (int i = 0; i < v.length; i++) if (v[i] == x) return i;
        return def;
    }

    /** Timer details (474): five rows that were always changed once and left alone, in one sheet. Keys unchanged. */
    private void pickSleepDetails() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(Ui.title(a, "淡出时长（到点前音量逐渐变小）"));
        final android.widget.RadioGroup g1 = radios(box, new String[]{"10 秒", "30 秒", "60 秒"}, indexOf(FADE_VALUES, Prefs.get(a).getInt(Prefs.SLEEP_FADE, 60), 2));
        box.addView(Ui.title(a, "延长按钮（通知栏、锁屏、角标上的「+N 分钟」）"));
        final android.widget.RadioGroup g2 = radios(box, new String[]{"+10 分钟", "+15 分钟", "+30 分钟"}, indexOf(STEP_VALUES, Prefs.get(a).getInt(Prefs.EXTEND_STEP, 15), 1));
        final android.widget.CheckBox hold = check(box, "暂停时定时也暂停（继续播放后接着倒数）", Prefs.on(a, Prefs.SLEEP_HOLD));
        final android.widget.CheckBox badge = check(box, "播放器左上角显示剩余时间（点一下延长，长按改时长）", Prefs.on(a, Prefs.SLEEP_BADGE));
        final android.widget.CheckBox black = check(box, "一键睡眠模式 10 秒后黑屏（双击恢复）", Prefs.on(a, Prefs.SLEEP_MODE_BLACK));
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("定时细节")
                .setView(sv)
                .setPositiveButton("确定", (d, w) -> {
                    Prefs.put(a, Prefs.SLEEP_FADE, FADE_VALUES[Math.max(0, checkedIndex(g1))]);
                    Prefs.put(a, Prefs.EXTEND_STEP, STEP_VALUES[Math.max(0, checkedIndex(g2))]);
                    Prefs.put(a, Prefs.SLEEP_HOLD, hold.isChecked());
                    Prefs.put(a, Prefs.SLEEP_BADGE, badge.isChecked());
                    Prefs.put(a, Prefs.SLEEP_MODE_BLACK, black.isChecked());
                    applied();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private android.widget.CheckBox check(LinearLayout parent, String text, boolean on) {
        android.widget.CheckBox cb = new android.widget.CheckBox(a);
        cb.setText(text);
        cb.setTextColor(Ui.TEXT);
        cb.setChecked(on);
        parent.addView(cb);
        return cb;
    }

    private String keepAliveLabel() {
        StringBuilder b = new StringBuilder();
        b.append(a.isIgnoringBattery() ? "电池优化 ✓" : "电池优化未关闭");
        b.append(" · ").append(a.notificationsOk() ? "通知 ✓" : "通知已关闭");
        if (a.fgsDeniedAt() > 0) b.append(" · 上次后台播放没能保持前台");
        b.append(" · 点此设置自启动和后台运行");
        return b.toString();
    }

    // ===================== settings search (442) =====================

    private static final String SEARCH_TITLE = "搜索设置";

    /** One setting found while building the pages off screen: its texts, where it is, how to get there. */
    private static final class Hit {
        final String title, sub, where;
        final List<Runnable> path;
        Hit(String title, String sub, String where, List<Runnable> path) { this.title = title; this.sub = sub; this.where = where; this.path = path; }
    }

    /** Off-screen walk over every page (442): page() builds into a throwaway box; nothing is clicked. */
    private final class Crawl {
        final List<Hit> hits = new ArrayList<>();
        final List<List<Runnable>> queue = new ArrayList<>();
        final List<List<String>> queueNames = new ArrayList<>();
        List<Runnable> path = new ArrayList<>();
        List<String> names = new ArrayList<>();
        void row(String title, String sub) {
            if (title == null || title.isEmpty() || SEARCH_TITLE.equals(title) || names.size() < 2) return;   // root rows are the groups themselves
            hits.add(new Hit(title, sub, android.text.TextUtils.join(" › ", names.subList(1, names.size())), path));
        }
        void child(Runnable open) {
            if (queue.size() >= 60) return;
            List<Runnable> p = new ArrayList<>(path);
            p.add(open);
            queue.add(p);
            queueNames.add(names);
        }
    }

    private Crawl crawl;
    private List<Hit> index;

    private void crawlPage(String title, Body body) {
        List<String> n = new ArrayList<>(crawl.names);
        n.add(title);
        crawl.names = n;
        try { body.build(new LinearLayout(a)); } catch (Throwable t) { Diag.log("settings-search", title + ": " + t); }
    }

    private List<Hit> buildIndex() {
        if (index != null) return index;
        int keep = refreshers.size();
        Crawl c = new Crawl();
        crawl = c;
        try {
            show();   // root: the five groups go into the queue
            for (int i = 0; i < c.queue.size(); i++) {
                List<Runnable> p = c.queue.get(i);
                c.path = p;
                c.names = c.queueNames.get(i);
                try { p.get(p.size() - 1).run(); } catch (Throwable t) { Diag.log("settings-search", String.valueOf(t)); }
            }
        } finally {
            crawl = null;
            while (refreshers.size() > keep) refreshers.remove(refreshers.size() - 1);
        }
        index = c.hits;
        return index;
    }

    private void searchPage() {
        final List<Hit> all = buildIndex();
        page(SEARCH_TITLE, box -> {
            final EditText in = new EditText(a);
            in.setSingleLine(true);
            in.setHint("例如：字幕、定时、屏蔽、画质");
            in.setHintTextColor(Ui.HINT);
            in.setTextColor(Ui.TEXT);
            in.setBackground(Ui.round(Ui.SURFACE, dp(Ui.RADIUS_DP)));
            in.setPadding(dp(16), dp(12), dp(16), dp(12));
            in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(16), dp(8), dp(16), dp(4));
            box.addView(in, lp);
            final TextView empty = Ui.text(a, "", 13, Ui.TEXT_2);
            empty.setPadding(dp(32), dp(8), dp(32), dp(8));
            box.addView(empty);
            final LinearLayout results = card(box);
            final Runnable update = () -> {
                results.removeAllViews();
                String q = String.valueOf(in.getText());
                int n = 0;
                for (final Hit h : all) {
                    if (!Logic.searchMatches(q, h.title, h.sub, h.where)) continue;
                    if (++n > 40) break;
                    results.addView(row(h.title, h.where + (h.sub.isEmpty() ? "" : " · " + h.sub), v -> {
                        for (Runnable r : h.path) r.run();
                    }));
                }
                results.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
                empty.setText(q.trim().isEmpty() ? "共 " + all.size() + " 项设置可搜索" : n == 0 ? "没有找到「" + q.trim() + "」相关的设置" : "");
                empty.setVisibility(empty.getText().length() == 0 ? View.GONE : View.VISIBLE);
            };
            in.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) { update.run(); }
            });
            update.run();
            in.requestFocus();
            in.postDelayed(() -> {
                android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (im != null) im.showSoftInput(in, 0);
            }, 250);
        });
    }

    /** Small explanation under a card (441): plain text, not a row that looks tappable. */
    private void footnote(LinearLayout box, String text) {
        TextView t = Ui.text(a, text, 12, Ui.TEXT_2);
        t.setPadding(dp(32), dp(2), dp(32), dp(8));
        box.addView(t);
    }

    /** Restore (440): from a file or from the clipboard. */
    private void restoreSheet() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView head = Ui.text(a, "恢复设置", 18, Ui.TEXT);
        head.setPadding(dp(20), dp(4), dp(20), dp(8));
        box.addView(head);
        final android.app.Dialog[] d = new android.app.Dialog[1];
        box.addView(Ui.sheetRow(a, "从文件恢复", "选择之前保存的备份文件", v -> { d[0].dismiss(); openBackupFile(); }));
        box.addView(Ui.sheetRow(a, "从剪贴板恢复", "先复制之前备份的那段文字，再点这里", v -> { d[0].dismiss(); importSettings(); }));
        d[0] = Ui.sheet(a, box);
        d[0].show();
    }

    /** Keep-alive sheet (238/390/292): every system page that keeps playback alive with the screen off. */
    private void keepAliveSheet() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView head = Ui.text(a, "后台保活", 18, Ui.TEXT);
        head.setPadding(dp(20), dp(4), dp(20), dp(8));
        box.addView(head);
        final android.app.Dialog[] d = new android.app.Dialog[1];
        box.addView(Ui.sheetRow(a, "电池优化", a.isIgnoringBattery() ? "已不受限制 ✓" : "点此关闭电池优化（息屏持续播放必需）", v -> { d[0].dismiss(); a.requestIgnoreBattery(); }));
        box.addView(Ui.sheetRow(a, "自启动", "ColorOS 自启动管理；找不到时打开应用详情", v -> { d[0].dismiss(); a.openAutostartSettings(); }));
        box.addView(Ui.sheetRow(a, "后台运行（耗电管理）", "ColorOS：允许后台运行 / 耗电策略无限制", v -> { d[0].dismiss(); a.openBatterySettings(); }));
        box.addView(Ui.sheetRow(a, "通知", a.notificationsOk() ? "已开启 ✓（锁屏控制需要）" : "已关闭：锁屏和通知栏无法控制播放，点此打开", v -> { d[0].dismiss(); a.openNotificationSettings(); }));
        box.addView(Ui.sheetRow(a, "应用详情", "耗电管理 → 允许后台运行", v -> { d[0].dismiss(); a.openAppDetails(); }));
        d[0] = Ui.sheet(a, box);
        d[0].setOnDismissListener(x -> refreshAll());
        d[0].show();
    }

    /** Merged default length (228): "same as the last one started" (sleepRemember) or a fixed mode. */
    private String sleepDefaultLabel() {
        int def = Prefs.sleepDefault(a);
        if (PrefsModel.sleepChoice(Prefs.sleepRemember(a), def) == PrefsModel.SLEEP_CHOICE_LAST) {
            return "沿用上次开启的（当前 " + SleepTimer.optionLabel(def) + "）· 通知栏按钮和一键睡眠模式用";
        }
        return SleepTimer.optionLabel(def) + " · 通知栏按钮和一键睡眠模式用";
    }

    private void pickSleepDefault() {
        final int[] opts = SleepTimer.OPTIONS;
        String[] labels = new String[opts.length + 1];
        labels[0] = "沿用上次开启的";
        for (int i = 0; i < opts.length; i++) labels[i + 1] = SleepTimer.optionLabel(opts[i]);
        int cur = PrefsModel.sleepChoice(Prefs.sleepRemember(a), Prefs.sleepDefault(a));
        int sel = cur == PrefsModel.SLEEP_CHOICE_LAST ? 0 : -1;
        for (int i = 0; i < opts.length; i++) if (opts[i] == cur) sel = i + 1;
        radio("默认时长", labels, sel, which -> {
            Prefs.PrefStore s = new Prefs.PrefStore(Prefs.get(a));
            PrefsModel.writeSleepChoice(s, which == 0 ? PrefsModel.SLEEP_CHOICE_LAST : opts[which - 1]);
            s.apply();
            applied();
        });
    }

    private String nightPromptLabel() {
        String w = PrefsModel.nightLabel(Prefs.num(a, Prefs.NIGHT_START), Prefs.num(a, Prefs.NIGHT_END));
        return Prefs.on(a, Prefs.NIGHT_PROMPT) ? "开 · " + w + " · 每晚最多一次" : "关";
    }

    /** One sheet (456): off / on with the current window / on and change the window. */
    private void pickNightPrompt() {
        String w = PrefsModel.nightLabel(Prefs.num(a, Prefs.NIGHT_START), Prefs.num(a, Prefs.NIGHT_END));
        radio("夜间打开时提示", new String[]{"不提示", "提示（" + w + "）", "提示，并修改时段"}, Prefs.on(a, Prefs.NIGHT_PROMPT) ? 1 : 0, which -> {
            Prefs.put(a, Prefs.NIGHT_PROMPT, which > 0);
            applied();
            if (which == 2) pickNight();
        });
    }

    /** Night window (307): start and end hour, one sheet each. */
    private void pickNight() {
        final String[] hours = new String[24];
        for (int i = 0; i < 24; i++) hours[i] = i + ":00";
        radio("夜间从几点开始", hours, Prefs.num(a, Prefs.NIGHT_START), start -> {
            radio("到几点结束", hours, Prefs.num(a, Prefs.NIGHT_END), end -> {
                if (start == end) { toastShort("开始和结束不能相同"); return; }
                Prefs.put(a, Prefs.NIGHT_START, start);
                Prefs.put(a, Prefs.NIGHT_END, end);
                applied();
            });
        });
    }

    private static final String[] NEXT_VALUES = {"rec", "channel", "playlist"};
    private static final String[] NEXT_LABELS = {"YouTube 推荐", "优先同频道", "只在播放列表内"};

    private String nextLabel() {
        return "平时：" + pick(NEXT_VALUES, NEXT_LABELS, Prefs.str(a, Prefs.NEXT_SOURCE, "rec"))
                + (Prefs.on(a, Prefs.SKIP_WATCHED_NEXT) && "rec".equals(Prefs.str(a, Prefs.NEXT_SOURCE, "rec")) ? "（跳过看过的）" : "")
                + (Prefs.on(a, Prefs.LIST_END_NEXT) ? " · 列表播完接推荐" : "")
                + " · 睡眠时：" + (Prefs.on(a, Prefs.SLEEP_CALM) ? (Prefs.on(a, Prefs.SLEEP_STRICT) ? "只同频道（没有就停）" : "同频道/列表") : "跟随平时")
                + " · 一键睡眠模式总是同频道";
    }

    /** Next video (231): "usual" -> nextSource, "while a sleep timer runs" -> sleepCalm. */
    private void pickNext() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(Ui.title(a, "平时"));
        final android.widget.RadioGroup g1 = radios(box, NEXT_LABELS, Math.max(0, java.util.Arrays.asList(NEXT_VALUES).indexOf(Prefs.str(a, Prefs.NEXT_SOURCE, "rec"))));
        // (311) off by default: recommended autoplay skips a target already watched
        final android.widget.CheckBox skip = new android.widget.CheckBox(a);
        skip.setText("推荐模式下跳过已看完的视频");
        skip.setTextColor(Ui.TEXT);
        skip.setChecked(Prefs.on(a, Prefs.SKIP_WATCHED_NEXT));
        box.addView(skip);
        // (313) off by default: a playlist played to its end goes on with a recommendation
        final android.widget.CheckBox listEnd = new android.widget.CheckBox(a);
        listEnd.setText("播放列表播完后接 YouTube 推荐（睡眠定时中总是停止）");
        listEnd.setTextColor(Ui.TEXT);
        listEnd.setChecked(Prefs.on(a, Prefs.LIST_END_NEXT));
        box.addView(listEnd);
        box.addView(Ui.title(a, "睡眠定时中"));
        // (315) third option: same channel only, the timer ends when there is none
        final android.widget.RadioGroup g2 = radios(box, new String[]{"跟随平时", "优先同频道 / 列表（找不到时照常推荐）", "只同频道 / 列表（找不到就停止并结束定时）"},
                Prefs.on(a, Prefs.SLEEP_CALM) ? (Prefs.on(a, Prefs.SLEEP_STRICT) ? 2 : 1) : 0);
        TextView note = Ui.text(a, "默认用 YouTube 自动播放；其他选项会关掉它由本 App 选。一键睡眠模式总是同频道。", 13, Ui.TEXT_2);
        note.setPadding(0, dp(8), 0, 0);
        box.addView(note);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("接下来播放")
                .setView(sv)
                .setPositiveButton("确定", (d, w) -> {
                    Prefs.put(a, Prefs.NEXT_SOURCE, NEXT_VALUES[Math.max(0, checkedIndex(g1))]);
                    int sl = checkedIndex(g2);
                    Prefs.put(a, Prefs.SLEEP_CALM, sl >= 1);
                    Prefs.put(a, Prefs.SLEEP_STRICT, sl == 2);
                    Prefs.put(a, Prefs.SKIP_WATCHED_NEXT, skip.isChecked());
                    Prefs.put(a, Prefs.LIST_END_NEXT, listEnd.isChecked());
                    applied();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** Notification layout (237): left/right keys -> notifSeek, fifth key -> notifWatchLater. */
    private void pickNotif() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(Ui.title(a, "左右两个键"));
        final android.widget.RadioGroup g1 = radios(box, new String[]{"上一个 / 下一个", "快退 10 秒 / 快进 30 秒"}, Prefs.on(a, Prefs.NOTIF_SEEK) ? 1 : 0);
        box.addView(Ui.title(a, "第五个键"));
        final android.widget.RadioGroup g2 = radios(box, new String[]{"稍后观看（需登录）", "不显示"}, Prefs.on(a, Prefs.NOTIF_WL) ? 0 : 1);
        box.addView(Ui.title(a, "耳机"));
        final android.widget.CheckBox multi = check(box, "耳机按键多击：一下暂停/播放，两下下一个，三下上一个", Prefs.on(a, Prefs.HEADSET_MULTI));
        TextView note = Ui.text(a, "睡眠定时中第五键让位给「+" + SleepTimer.extendMin(a) + " 分钟」；暂停时第五键是「关闭」；未登录时不显示「稍后观看」。Android 13+ 的锁屏/下拉媒体卡片同样生效。", 13, Ui.TEXT_2);
        note.setPadding(0, dp(8), 0, 0);
        box.addView(note);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("通知栏按钮")
                .setView(sv)
                .setPositiveButton("确定", (d, w) -> {
                    Prefs.put(a, Prefs.NOTIF_SEEK, checkedIndex(g1) == 1);
                    Prefs.put(a, Prefs.NOTIF_WL, checkedIndex(g2) == 0);
                    Prefs.put(a, Prefs.HEADSET_MULTI, multi.isChecked());
                    applied();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===================== 3. 首页与内容 =====================

    private void listPage() {
        page("首页与内容", box -> {
            LinearLayout h = card(box, "首页");
            h.addView(choice("打开 App 时进入", "从桌面图标启动时的第一页", Prefs.START_PAGE,
                    new String[]{"首页", "订阅"}, new String[]{"home", "subs"}, "home"));
            h.addView(dyn("隐藏已看完的视频", this::watchedLabel, v -> pickWatched()));   // + related videos (458)
            h.addView(dyn("下拉刷新", this::refreshLabel, v -> pickRefresh()));            // light refresh + count (457)
            LinearLayout f = card(box, "内容过滤（首页和「接下来播放」）");
            f.addView(sub("内容过滤", this::filterLabel, this::filterPage));
            LinearLayout m = card(box, "隐藏模块与搜索");
            m.addView(toggle("隐藏首页货架", "新闻、合集、「可能喜欢」等横向模块", Prefs.HIDE_SHELVES));
            // two search switches became one (459); "open the keyboard" lost its row (460), searchFocus keeps its value
            m.addView(toggle("搜索结果只留视频和频道", "隐藏「人们还看了」等插入模块和自动生成的合辑",
                    () -> PrefsModel.searchTidy(Prefs.on(a, Prefs.HIDE_SEARCH_EXTRAS), Prefs.on(a, Prefs.HIDE_SEARCH_MIX)),
                    on -> { Prefs.PrefStore st = new Prefs.PrefStore(Prefs.get(a)); PrefsModel.writeSearchTidy(st, on); st.apply(); }));
        });
    }

    private static final int[] WATCHED_VALUES = {0, 50, 80, 95};
    private static final String[] WATCHED_LABELS = {"不隐藏", "看过 50%", "看过 80%（默认）", "看过 95%"};

    /** Merged watched row (229): "don't hide" keeps seenMin for when it is switched on again. */
    private String watchedLabel() {
        int c = PrefsModel.watchedChoice(Prefs.hideWatched(a), Prefs.get(a).getInt(Prefs.SEEN_MIN, 80));
        String l = WATCHED_LABELS[0];
        for (int i = 0; i < WATCHED_VALUES.length; i++) if (WATCHED_VALUES[i] == c) l = WATCHED_LABELS[i];
        if (c == 0) return l;
        return l + (Prefs.on(a, Prefs.HIDE_WATCHED_RELATED) ? " · 首页和相关视频" : " · 只在首页") + " · 看了一部分的保留";
    }

    private void pickWatched() {
        int c = PrefsModel.watchedChoice(Prefs.hideWatched(a), Prefs.get(a).getInt(Prefs.SEEN_MIN, 80));
        int sel = 0;
        for (int i = 0; i < WATCHED_VALUES.length; i++) if (WATCHED_VALUES[i] == c) sel = i;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        final android.widget.RadioGroup g = radios(box, WATCHED_LABELS, sel);
        // (458) the related-videos switch lives here now
        final android.widget.CheckBox rel = new android.widget.CheckBox(a);
        rel.setText("播放页的相关视频也隐藏");
        rel.setTextColor(Ui.TEXT);
        rel.setChecked(Prefs.on(a, Prefs.HIDE_WATCHED_RELATED));
        box.addView(rel);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("隐藏已看完的视频")
                .setView(sv)
                .setPositiveButton("确定", (d, w) -> {
                    Prefs.PrefStore s = new Prefs.PrefStore(Prefs.get(a));
                    PrefsModel.writeWatchedChoice(s, WATCHED_VALUES[Math.max(0, checkedIndex(g))]);
                    s.put(Prefs.HIDE_WATCHED_RELATED, rel.isChecked());
                    s.apply();
                    applied();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String refreshLabel() {
        return (Prefs.on(a, Prefs.LIGHT_REFRESH) ? "轻量刷新（实验）" : "完整刷新")
                + (Prefs.on(a, Prefs.REFRESH_COUNT) ? " · 提示新视频数" : "");
    }

    /** Pull to refresh (457): full / light, and the "N new videos" toast, in one sheet. */
    private void pickRefresh() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        final android.widget.RadioGroup g = radios(box, new String[]{"完整刷新（默认，最容易出新视频）", "轻量刷新（实验：保留离线缓存，更快；没出新视频就改回）"},
                Prefs.on(a, Prefs.LIGHT_REFRESH) ? 1 : 0);
        final android.widget.CheckBox cnt = new android.widget.CheckBox(a);
        cnt.setText("刷新后提示「更新了 N 个新视频」");
        cnt.setTextColor(Ui.TEXT);
        cnt.setChecked(Prefs.on(a, Prefs.REFRESH_COUNT));
        box.addView(cnt);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("下拉刷新")
                .setView(sv)
                .setPositiveButton("确定", (d, w) -> {
                    Prefs.put(a, Prefs.LIGHT_REFRESH, checkedIndex(g) == 1);
                    Prefs.put(a, Prefs.REFRESH_COUNT, cnt.isChecked());
                    applied();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String filterLabel() {
        int n = 0;
        if (!Prefs.lines(a, Prefs.BLOCK_CHANNELS).isEmpty()) n++;
        if (!Prefs.lines(a, Prefs.BLOCK_WORDS).isEmpty()) n++;
        if (Prefs.get(a).getInt(Prefs.MIN_DUR, 0) > 0) n++;
        if (Prefs.get(a).getInt(Prefs.MAX_AGE, 0) > 0) n++;
        if (Prefs.on(a, Prefs.HIDE_LIVE)) n++;
        return (n == 0 ? "未开启" : "已开 " + n + " 项") + " · 频道、关键词、短视频、旧视频、直播、本地隐藏";
    }

    /** Every reason a video can be missing, in one page (235). */
    private void filterPage() {
        page("内容过滤", box -> {
            LinearLayout f = card(box);
            f.addView(dyn("屏蔽的频道", () -> countLabel(Prefs.BLOCK_CHANNELS, "长按视频卡片 →「不再显示此频道」也能加"),
                    v -> editLines("屏蔽的频道", "每行一个频道名；长按菜单加入的形如「@频道号|频道名」，改名后也有效", Prefs.BLOCK_CHANNELS)));
            f.addView(dyn("屏蔽的关键词", () -> countLabel(Prefs.BLOCK_WORDS, "标题含这些词就不显示，搜索结果也生效；支持 re: 正则和 ! 放行词"),
                    v -> editLines("屏蔽的关键词", "每行一个词，不区分大小写；re: 开头是正则（≤100 字），! 开头是放行词（标题含它就不按关键词屏蔽）", Prefs.BLOCK_WORDS)));
            f.addView(choiceInt("隐藏短视频", "按卡片上的时长判断", Prefs.MIN_DUR,
                    new String[]{"不隐藏", "短于 1 分钟", "短于 3 分钟", "短于 5 分钟", "短于 10 分钟"}, new int[]{0, 1, 3, 5, 10}, 0));
            f.addView(choiceInt("隐藏旧视频", "按「x 天前」这类发布时间判断", Prefs.MAX_AGE,
                    new String[]{"不隐藏", "超过 7 天", "超过 30 天", "超过半年", "超过 1 年"}, new int[]{0, 7, 30, 180, 365}, 0));
            f.addView(toggle("隐藏直播、首映和即将开播", "只在首页和「接下来播放」里生效", Prefs.HIDE_LIVE));
            LinearLayout l = card(box, "本地");
            l.addView(dyn("本地隐藏的视频", () -> (hiddenCount < 0 ? "长按菜单里「本地隐藏」过的视频" : hiddenCount + " 个")
                    + " · 点此全部恢复", v -> {
                MainActivity.js("ytl.unhideAll()");
                hiddenCount = 0;
                refreshAll();
                toastShort("已恢复");
            }));
            l.addView(row("清空屏蔽列表", "删除全部屏蔽的频道和关键词（「恢复默认」不会动它们）", v -> confirmClearBlocks()));
            a.evalJs("window.ytl&&ytl.hiddenCount?ytl.hiddenCount():-1", r -> {
                try { hiddenCount = r == null || "null".equals(r) ? -1 : (int) Double.parseDouble(r); } catch (Exception e) { hiddenCount = -1; }
                refreshAll();
            });
        });
    }

    private void confirmClearBlocks() {
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("清空屏蔽列表？")
                .setMessage("屏蔽的频道 " + Prefs.lines(a, Prefs.BLOCK_CHANNELS).size() + " 个、关键词 " + Prefs.lines(a, Prefs.BLOCK_WORDS).size() + " 个会被删除。")
                .setPositiveButton("清空", (d, w) -> {
                    Prefs.clearBlockLists(a);
                    applied();
                    toastShort("已清空屏蔽列表");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===================== 4. 外观与交互 =====================

    private void lookPage() {
        page("外观与交互", box -> {
            LinearLayout c = card(box, "主题与手感");
            c.addView(toggle("纯黑主题（AMOLED）", "深色模式下背景纯黑，更省电、夜里不刺眼", Prefs.AMOLED));
            // accent (463): only offered while a colour other than the brand blue is still set
            if (!accentIsDefault()) c.addView(accentRow());
            c.addView(fontRow());   // two font switches became one choice (455)
            // the long-press menu and keep-scroll switches lost their rows (477): both stay on (the defaults)
            LinearLayout l = card(box, "列表");
            l.addView(dyn("列表样式", this::listStyleLabel, v -> pickListStyle()));       // compact / uniform cards / dim thumbnails (476)
            l.addView(dyn("滑动流畅度", this::perfLabel, v -> pickPerf()));               // list rendering + off-screen pre-raster (476)
        });
    }

    private String listStyleLabel() {
        return PrefsModel.listStyleLabel(Prefs.on(a, Prefs.COMPACT), Prefs.on(a, Prefs.CARD_STYLE), Prefs.on(a, Prefs.DIM_THUMBS));
    }

    /** List look (476): compact layout, uniform card style and dimmed thumbnails in one sheet. */
    private void pickListStyle() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        final android.widget.CheckBox compact = check(box, "首页紧凑列表（小图在左、标题在右，一屏看更多）", Prefs.on(a, Prefs.COMPACT));
        final android.widget.CheckBox card = check(box, "统一卡片样式（缩略图圆角统一、标题两行、时长和进度条更醒目）", Prefs.on(a, Prefs.CARD_STYLE));
        final android.widget.CheckBox dim = check(box, "深色模式压暗缩略图（夜里看列表不刺眼）", Prefs.on(a, Prefs.DIM_THUMBS));
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("列表样式")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    Prefs.put(a, Prefs.COMPACT, compact.isChecked());
                    Prefs.put(a, Prefs.CARD_STYLE, card.isChecked());
                    Prefs.put(a, Prefs.DIM_THUMBS, dim.isChecked());
                    applied();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String perfLabel() {
        return PrefsModel.perfLabel(Prefs.on(a, Prefs.PERF_CSS), Prefs.on(a, Prefs.PRE_RASTER));
    }

    /** Smoothness (476): list rendering optimisation and the off-screen pre-raster experiment. */
    private void pickPerf() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        final android.widget.CheckBox perf = check(box, "列表渲染优化（屏幕外的卡片不渲染、去掉骨架闪烁；排版异常时可关闭）", Prefs.on(a, Prefs.PERF_CSS));
        final android.widget.CheckBox ras = check(box, "预渲染屏外内容（实验：滑动更跟手，但更耗内存和电；可用「关于与诊断 → 流畅度自测」对比）", Prefs.on(a, Prefs.PRE_RASTER));
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("滑动流畅度")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    Prefs.put(a, Prefs.PERF_CSS, perf.isChecked());
                    Prefs.put(a, Prefs.PRE_RASTER, ras.isChecked());
                    applied();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // accent (103/351/361/463): the brand blue is the only colour now; an older pick can be set back
    private static final String[] ACCENT_LABELS = {"品牌蓝", "天蓝", "青", "紫", "橙", "粉"};
    private static final String[] ACCENT_VALUES = {"", "#3EA6FF", "#00BFA5", "#9C6BFF", "#FF8A00", "#FF4F8B"};

    private boolean accentIsDefault() {
        String cur = Prefs.str(a, Prefs.ACCENT, "");
        return cur.isEmpty() || "#2F6BFF".equalsIgnoreCase(cur);
    }

    private View accentRow() {
        return valueRow("强调色", "点此改回默认的品牌蓝", () -> {
            String cur = Prefs.str(a, Prefs.ACCENT, "");
            for (int i = 0; i < ACCENT_VALUES.length; i++) if (ACCENT_VALUES[i].equalsIgnoreCase(cur)) return ACCENT_LABELS[i];
            return cur;
        }, v -> {
            Prefs.put(a, Prefs.ACCENT, "");
            applied();
            reopen();   // titles, switches and values pick the colour up
        });
    }

    private static final String[] FONT_LABELS = {"固定大小", "跟随系统（85%–130%）", "跟随系统，允许超大（≤160%）"};

    /** Web text size (455): fontFollow + fontLarge behind one three-way choice. */
    private View fontRow() {
        return valueRow("网页字号", "跟随系统字体大小；超大时标题最多三行，个别页面可能变挤",
                () -> FONT_LABELS[PrefsModel.fontChoice(Prefs.on(a, Prefs.FONT_FOLLOW), Prefs.on(a, Prefs.FONT_LARGE))].replaceAll("（.*）", ""),
                v -> radio("网页字号", FONT_LABELS, PrefsModel.fontChoice(Prefs.on(a, Prefs.FONT_FOLLOW), Prefs.on(a, Prefs.FONT_LARGE)), which -> {
                    Prefs.PrefStore st = new Prefs.PrefStore(Prefs.get(a));
                    PrefsModel.writeFontChoice(st, which);
                    st.apply();
                    applied();
                }));
    }

    // ===================== 5. 数据与更新 =====================

    private void dataPage() {
        page("数据与更新", box -> {
            LinearLayout u = card(box, "更新");
            u.addView(dyn("检查更新", () -> {
                String r = Updater.readyTag(a);
                String last = Prefs.str(a, Prefs.UPDATE_LAST_RESULT, "");
                return r != null ? "有新版本 " + r + "，点此更新" : "当前 " + version() + " · 每天自动检查一次"
                        + (last.isEmpty() ? "（有新版只显示小红点）" : " · 上次：" + last);
            }, v -> Updater.checkNow(a)));
            u.addView(toggle("国内网络用镜像下载", "下载慢时开启；安装包都会做 SHA-256 校验，失败自动改回直连", Prefs.UPDATE_MIRROR));
            u.addView(dyn("远程屏蔽规则", () -> (Prefs.on(a, Prefs.REMOTE_RULES) ? "自动更新 · " : "已关闭 · ") + RemoteRules.status(a), v -> rulesDialog()));
            LinearLayout b = card(box, "备份与恢复");
            // four rows that only differed in where the backup goes became two (440)
            // straight to a file (466); clipboard export removed, clipboard restore kept for old copies
            b.addView(row("备份设置", "保存为 .json 文件，可选带上本地隐藏和进度", v -> withLocal(this::saveBackupFile)));
            b.addView(row("恢复设置", "从文件或剪贴板恢复，先看会改哪些；10 秒内可撤销", v -> restoreSheet()));
            b.addView(row("恢复默认设置", "开关和选项回到默认；屏蔽列表、登录和记录不动；10 秒内可撤销", v -> confirmDefaults()));
            // the history row and two explanation lines went (464); report + logs share one page (465)
            LinearLayout d = card(box);
            d.addView(nav(0, "关于与诊断", "版本、WebView、刷新率、流畅度自测、问题报告、日志", this::logsPage));   // two pages became one (472)
        });
    }

    private void rulesDialog() {
        final boolean on = Prefs.on(a, Prefs.REMOTE_RULES);
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("远程屏蔽规则")
                .setMessage("只从本 App 的 GitHub 仓库拉取并校验（SHA-256），失败用内置规则。\n\n状态：" + RemoteRules.status(a))
                .setPositiveButton("立即检查", (d, w) -> RemoteRules.checkNow(a, this::refreshAll))
                .setNeutralButton(on ? "关闭自动更新" : "开启自动更新", (d, w) -> { Prefs.put(a, Prefs.REMOTE_RULES, !on); applied(); })
                .setNegativeButton("关闭", null)
                .show();
    }

    private static final String REFRESH_HINT = " · 滑动时大多没到最高刷新率，可能被系统按应用限制了；点此打开显示设置，在「屏幕刷新率 → 应用」里设为高刷";

    private void logsPage() {
        page("关于与诊断", box -> {
            LinearLayout c = card(box);
            c.addView(row(a.getString(R.string.app_name) + " " + version(), "个人自用的精简 YouTube 客户端 · 包名 " + a.getPackageName(), null));
            c.addView(row("WebView", MainActivity.webViewVersion(a), null));
            if (a.refreshLimited()) {
                c.addView(row("屏幕刷新率", a.refreshRateLabel() + REFRESH_HINT, v -> a.openDisplaySettings()));
            } else {
                c.addView(row("屏幕刷新率", a.refreshRateLabel(), null));
            }
            c.addView(dyn("睡眠定时耗电", () -> {
                String n = NightLog.last(a);
                return n.isEmpty() ? "开睡眠定时睡一晚后显示（充电中或不到 10 分钟的不记）" : n;
            }, null));
            c.addView(row("流畅度自测", "在首页列表上自动滚动 10 秒，统计掉帧并写入诊断日志", v -> { closeAll(); a.runSmoothTest(); }));
            LinearLayout r = card(box, "问题与日志");
            r.addView(row("复制问题报告", "版本、WebView、设备、设置摘要和最近日志，粘贴发给我", v -> copyReport()));
            r.addView(dyn("复制漏网广告记录", () -> Diag.lines(a, Diag.ADS) + " 条", v -> copyLog(Diag.ADS, "漏网广告记录")));
            r.addView(dyn("复制诊断日志", () -> Diag.lines(a, Diag.DIAG) + " 行 · 只保留最近 30 天", v -> copyLog(Diag.DIAG, "诊断日志")));
            r.addView(dyn("复制崩溃记录", () -> { String k = Diag.lastCrash(a); return k.isEmpty() ? "没有崩溃记录" : "最近 3 次崩溃的完整堆栈"; },
                    v -> copyLog(Diag.CRASH, "崩溃记录")));
            r.addView(dyn("网络拦截统计", com.google.android.youtube.pro.webview.AdBlocker::summary, null));
            r.addView(row("清空以上记录", "只清本机日志", v -> {
                Diag.clear(a, Diag.ADS);
                Diag.clear(a, Diag.DIAG);
                Diag.clear(a, Diag.CRASH);
                refreshAll();
                toastShort("已清空");
            }));
        });
    }

    // ===================== page frame =====================

    private interface Body { void build(LinearLayout box); }

    /*
     * One full-screen window for every settings page (365): pages are views stacked in it, a new one
     * slides in from the right in 200 ms (property animations, smooth at 120 Hz), back slides it out.
     * Playback, background quality and the page script keep running underneath as before.
     */
    private Dialog win;
    private FrameLayout stack;
    private final List<View> pages = new ArrayList<>();
    private final List<Integer> pageRefreshers = new ArrayList<>();
    private Object backCb;

    private void ensureWindow() {
        if (win != null) return;
        final Dialog d = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar);
        stack = new FrameLayout(a);
        stack.setBackgroundColor(Ui.BG);
        stack.setFitsSystemWindows(true);
        if (android.os.Build.VERSION.SDK_INT >= 29) stack.setForceDarkAllowed(false);   // already dark (392)
        d.setContentView(stack);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setWindowAnimations(android.R.style.Animation_Activity);
            w.setStatusBarColor(Ui.BG);
            w.setNavigationBarColor(Ui.BG);
        }
        // back goes one page up, the last page closes the window
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback cb = this::pop;
            d.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            backCb = cb;
        }
        d.setOnKeyListener((dlg, code, ev) -> {
            if (code != android.view.KeyEvent.KEYCODE_BACK) return false;
            if (ev.getAction() == android.view.KeyEvent.ACTION_UP) pop();
            return true;
        });
        d.setOnDismissListener(x -> { win = null; stack = null; pages.clear(); pageRefreshers.clear(); refreshers.clear(); });
        win = d;
        d.show();
    }

    private void page(String title, Body body) {
        if (crawl != null) { crawlPage(title, body); return; }   // settings search index (442)
        ensureWindow();
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setClickable(true);   // a page under the top one never gets its touches

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(16), dp(4));
        ImageButton back = new ImageButton(a);
        back.setImageResource(R.drawable.ic_arrow_back_white);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT));
        back.setBackground(null);
        Ui.ripple(back);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> pop());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = Ui.text(a, title, 20, Ui.TEXT);
        t.setPadding(dp(8), 0, 0, 0);
        Ui.heading(t);   // (379)
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        ScrollView scroll = new ScrollView(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, dp(24));
        // at most 720 dp wide, centred, on a tablet or in landscape (385)
        FrameLayout center = new FrameLayout(a);
        center.addView(box, new FrameLayout.LayoutParams(Ui.pageMaxWidth(a), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        scroll.addView(center);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        pageRefreshers.add(refreshers.size());
        body.build(box);

        final View under = pages.isEmpty() ? null : pages.get(pages.size() - 1);
        stack.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pages.add(root);
        if (under != null) {
            int ms = Ui.anim(a, Ui.ANIM_MS);
            root.setTranslationX(dp(48));
            root.setAlpha(0f);
            root.animate().translationX(0f).alpha(1f).setDuration(ms).withEndAction(() -> under.setVisibility(View.INVISIBLE)).start();
            if (ms == 0) under.setVisibility(View.INVISIBLE);
            root.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }
    }

    /** One page back; the root page closes the window. */
    private void pop() {
        if (win == null) return;
        if (pages.size() <= 1) { closeAll(); return; }
        final View top = pages.remove(pages.size() - 1);
        int keep = pageRefreshers.remove(pageRefreshers.size() - 1);
        while (refreshers.size() > keep) refreshers.remove(refreshers.size() - 1);
        View under = pages.get(pages.size() - 1);
        under.setVisibility(View.VISIBLE);
        refreshAll();
        final FrameLayout st = stack;
        top.animate().translationX(dp(48)).alpha(0f).setDuration(Ui.anim(a, 150)).withEndAction(() -> {
            if (st != null) st.removeView(top);
        }).start();
        under.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    private void closeAll() {
        Dialog d = win;
        if (d == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 33 && backCb != null) {
            try { d.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCb); } catch (Exception ignored) {}
        }
        try { d.dismiss(); } catch (Exception ignored) {}
    }

    private LinearLayout card(LinearLayout parent) { return card(parent, null); }

    private LinearLayout card(LinearLayout parent, String title) {
        if (title != null) parent.addView(Ui.title(a, title));
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(Ui.SURFACE, dp(Ui.RADIUS_DP)));
        c.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(title == null ? 12 : 2), dp(16), dp(4));
        parent.addView(c, lp);
        return c;
    }

    // ===================== rows =====================

    private LinearLayout baseRow() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setMinimumHeight(dp(Ui.ROW_MIN_DP));
        l.setPadding(dp(16), dp(10), dp(12), dp(10));
        return l;
    }

    private LinearLayout texts(String title, TextView sub) {
        if (crawl != null) crawl.row(title, sub == null ? "" : String.valueOf(sub.getText()));
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Ui.text(a, title, 16, Ui.TEXT));
        if (sub != null) t.addView(sub);
        return t;
    }

    private View row(String title, String sub, View.OnClickListener click) {
        LinearLayout l = baseRow();
        l.addView(texts(title, sub == null ? null : Ui.text(a, sub, 13, Ui.TEXT_2)), new LinearLayout.LayoutParams(0, -2, 1f));
        if (click != null) { Ui.ripple(l); l.setOnClickListener(click); }
        l.setContentDescription(sub == null ? title : title + "，" + sub);
        return l;
    }

    private interface Label { String get(); }

    /** Row whose explanation is computed, refreshed when a sub page closes or a value changes. */
    private View dyn(String title, final Label label, View.OnClickListener click) {
        LinearLayout l = baseRow();
        final TextView sub = Ui.text(a, "", 13, Ui.TEXT_2);
        l.addView(texts(title, sub), new LinearLayout.LayoutParams(0, -2, 1f));
        Runnable r = () -> { try { sub.setText(label.get()); } catch (Exception ignored) {} };
        r.run();
        refreshers.add(r);
        if (click != null) { Ui.ripple(l); l.setOnClickListener(click); }
        l.setContentDescription(title);
        return l;
    }

    private View nav(int icon, String title, String sub, final Runnable open) {
        if (crawl != null) crawl.child(open);
        LinearLayout l = baseRow();
        if (icon != 0) {
            android.widget.ImageView iv = new android.widget.ImageView(a);
            iv.setImageResource(icon);
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT_2));
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(24), dp(24));
            ip.rightMargin = dp(16);
            l.addView(iv, ip);
        }
        l.addView(texts(title, Ui.text(a, sub, 13, Ui.TEXT_2)), new LinearLayout.LayoutParams(0, -2, 1f));
        l.addView(chevron());
        Ui.ripple(l);
        l.setOnClickListener(v -> open.run());
        l.setContentDescription(title + "，" + sub);
        return l;
    }

    private View toggle(String title, String sub, final String key) {
        return toggle(title, sub, () -> Prefs.on(a, key), on -> Prefs.put(a, key, on));
    }

    private interface BoolGet { boolean get(); }
    private interface BoolSet { void set(boolean on); }

    /** Switch row over any stored state, e.g. a merged row that writes two keys (459). */
    private View toggle(String title, String sub, final BoolGet get, final BoolSet set) {
        final LinearLayout l = baseRow();
        l.addView(texts(title, Ui.text(a, sub, 13, Ui.TEXT_2)), new LinearLayout.LayoutParams(0, -2, 1f));
        final Switch sw = new Switch(a);
        sw.setChecked(get.get());
        tint(sw);
        // screen reader (373): one stop per row, read once with its state; the switch itself is not a second stop
        sw.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sw.setFocusable(false);
        l.setContentDescription(title + "，" + sub);
        stateDesc(l, sw.isChecked());
        sw.setOnCheckedChangeListener((b, checked) -> {
            set.set(checked);
            stateDesc(l, checked);
            applied();
        });
        l.addView(sw);
        Ui.ripple(l);
        l.setOnClickListener(v -> sw.toggle());
        return l;
    }

    private static void stateDesc(View v, boolean on) {
        if (android.os.Build.VERSION.SDK_INT >= 30) v.setStateDescription(on ? "已开启" : "已关闭");
        else v.setContentDescription(String.valueOf(v.getContentDescription()).replaceAll("，已(开启|关闭)$", "") + (on ? "，已开启" : "，已关闭"));
    }

    private void tint(Switch sw) {
        Ui.styleSwitch(sw);   // (355)
    }

    /**
     * Row with its current value at the right end in the accent colour (360); the explanation stays
     * underneath, at most two lines.
     */
    private View valueRow(String title, String note, final Label value, View.OnClickListener click) {
        LinearLayout l = baseRow();
        TextView sub = note == null ? null : Ui.text(a, note, 13, Ui.TEXT_2);
        if (sub != null) { sub.setMaxLines(2); sub.setEllipsize(android.text.TextUtils.TruncateAt.END); }
        l.addView(texts(title, sub), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView val = Ui.text(a, "", 14, Ui.accentText(a));
        val.setPadding(dp(12), 0, 0, 0);
        val.setMaxWidth(dp(140));
        val.setGravity(Gravity.END);
        l.addView(val);
        Runnable r = () -> {
            try {
                String s = value.get();
                val.setText(s);
                l.setContentDescription(title + "，当前：" + s + (note == null ? "" : "，" + note));
            } catch (Exception ignored) {}
        };
        r.run();
        refreshers.add(r);
        if (click != null) { Ui.ripple(l); l.setOnClickListener(click); }
        return l;
    }

    private View choice(final String title, final String note, final String key, final String[] labels, final String[] values, final String def) {
        final Label lab = () -> {
            String cur = currentStr(key, def);
            String label = labels[0];
            for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) label = labels[i];
            return label;
        };
        final View[] self = new View[1];
        self[0] = valueRow(title, note, lab, v -> {
            String cur = currentStr(key, def);
            int sel = 0;
            for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) sel = i;
            Ui.choiceSheet(a, title, labels, null, sel, which -> {   // bottom sheet (353)
                putStr(key, values[which]);
                applied();
            });
        });
        return self[0];
    }

    private String currentStr(String key, String def) {
        return Prefs.str(a, key, def);
    }

    private void putStr(String key, String v) {
        Prefs.put(a, key, v);
    }

    /** Two-option row stored straight in a boolean key (236: no more pseudo key for the layout). */
    private View boolChoice(final String title, final String note, final String key, final String offLabel, final String onLabel) {
        return valueRow(title, note, () -> Prefs.on(a, key) ? onLabel : offLabel,
                v -> radio(title, new String[]{offLabel, onLabel}, Prefs.on(a, key) ? 1 : 0, which -> {
                    Prefs.put(a, key, which == 1);
                    applied();
                }));
    }

    /** Row that opens a second-level page, with a summary of what is set there. */
    private View sub(String title, Label label, final Runnable open) {
        if (crawl != null) crawl.child(open);
        LinearLayout l = (LinearLayout) dyn(title, label, v -> open.run());
        l.addView(chevron());
        return l;
    }

    /** Vector chevron instead of a "›" character (354). */
    private View chevron() {
        android.widget.ImageView iv = new android.widget.ImageView(a);
        iv.setImageResource(R.drawable.ic_chevron_right);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT_2));
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24), dp(24));
        lp.leftMargin = dp(8);
        iv.setLayoutParams(lp);
        return iv;
    }

    private static String pick(String[] values, String[] labels, String cur) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) return labels[i];
        return labels[0];
    }

    private interface IntCb { void on(int which); }

    private void radio(String title, String[] labels, int sel, final IntCb cb) {
        Ui.choiceSheet(a, title, labels, null, sel, cb::on);   // bottom sheet (353)
    }

    private android.widget.RadioGroup radios(LinearLayout parent, String[] labels, int sel) {
        android.widget.RadioGroup g = new android.widget.RadioGroup(a);
        for (int i = 0; i < labels.length; i++) {
            android.widget.RadioButton b = new android.widget.RadioButton(a);
            b.setId(View.generateViewId());
            b.setText(labels[i]);
            b.setTextColor(Ui.TEXT);
            b.setTextSize(15);
            b.setPadding(dp(8), dp(8), dp(8), dp(8));
            g.addView(b);
            if (i == sel) g.check(b.getId());
        }
        parent.addView(g);
        return g;
    }

    private static int checkedIndex(android.widget.RadioGroup g) {
        int id = g.getCheckedRadioButtonId();
        for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i).getId() == id) return i;
        return -1;
    }

    private View choiceInt(final String title, final String note, final String key, final String[] labels, final int[] values, final int def) {
        final Label lab = () -> {
            int cur = Prefs.get(a).getInt(key, def);
            String label = String.valueOf(cur);
            for (int i = 0; i < values.length; i++) if (values[i] == cur) label = labels[i];
            return label;
        };
        return valueRow(title, note, lab, v -> {
            int cur = Prefs.get(a).getInt(key, def);
            int sel = 0;
            for (int i = 0; i < values.length; i++) if (values[i] == cur) sel = i;
            Ui.choiceSheet(a, title, labels, null, sel, which -> {   // bottom sheet (353)
                Prefs.put(a, key, values[which]);
                applied();
            });
        });
    }

    // ===================== actions =====================

    private void applied() {
        a.applySettings();
        refreshAll();
    }

    private void refreshAll() {
        for (Runnable r : refreshers) r.run();
    }

    private String countLabel(String key, String note) {
        int n = Prefs.lines(a, key).size();
        return (n == 0 ? "未设置" : n + " 个") + " · " + note;
    }

    private void editLines(String title, String hint, final String key) {
        final EditText e = new EditText(a);
        e.setText(Prefs.str(a, key, ""));
        e.setHint(hint);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.HINT);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setMinLines(4);
        e.setGravity(Gravity.TOP | Gravity.START);
        FrameLayout f = new FrameLayout(a);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(e);
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setView(f)
                .setPositiveButton("保存", (d, w) -> {
                    Prefs.put(a, key, e.getText().toString());
                    // normalise (trim, dedupe, cap) by re-saving the parsed list
                    StringBuilder b = new StringBuilder();
                    for (String s : Prefs.lines(a, key)) { if (b.length() > 0) b.append('\n'); b.append(s); }
                    Prefs.put(a, key, b.toString());
                    applied();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---- local data in a backup (403): hidden videos + local resume positions, chosen per backup ----
    private static String pendingLocal = null;

    /** Ask what goes into the backup, read the page's local data if wanted, then run {@code next}. */
    private void withLocal(final Runnable next) {
        Ui.choiceSheet(a, "备份内容", new String[]{"只备份设置", "设置 + 本地隐藏的视频和观看进度"}, null, -1, which -> {
            if (which == 0) { pendingLocal = null; next.run(); return; }
            a.evalJs("window.ytl&&ytl.localData?JSON.stringify(ytl.localData()):''", r -> {
                pendingLocal = jsString(r);
                if (pendingLocal == null || !pendingLocal.startsWith("{")) { pendingLocal = null; toastShort("页面还没准备好，只备份了设置"); }
                next.run();
            });
        });
    }

    /** evaluateJavascript hands back a JSON-encoded string: decode it. */
    private static String jsString(String r) {
        try { return r == null || "null".equals(r) ? null : new org.json.JSONArray("[" + r + "]").getString(0); } catch (Exception e) { return null; }
    }

    // ---- backup files (402): the system picker, no storage permission ----
    private static SettingsPage filePage;   // the settings screen that started the picker

    private void saveBackupFile() {
        try {
            String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("application/json")
                    .putExtra(android.content.Intent.EXTRA_TITLE, a.getString(R.string.app_name) + "-设置-" + day + ".json");
            filePage = this;
            a.startActivityForResult(i, MainActivity.REQ_BACKUP_SAVE);
        } catch (Exception e) {
            toastShort("无法打开文件选择器");
        }
    }

    private void openBackupFile() {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(android.content.Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
            filePage = this;
            a.startActivityForResult(i, MainActivity.REQ_BACKUP_OPEN);
        } catch (Exception e) {
            toastShort("无法打开文件选择器");
        }
    }

    public static void onBackupSaved(MainActivity a, android.net.Uri uri) {
        try (java.io.OutputStream os = a.getContentResolver().openOutputStream(uri, "wt")) {
            if (os == null) throw new java.io.IOException("no stream");
            os.write(Prefs.exportJsonWithLocal(a, Updater.installedVersion(a), pendingLocal).getBytes("UTF-8"));
            Toast.makeText(a, pendingLocal != null ? "设置和本地数据已保存到文件" : "设置已保存到文件", Toast.LENGTH_SHORT).show();
            pendingLocal = null;
        } catch (Exception e) {
            Diag.log("backup", "save: " + e);
            Toast.makeText(a, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static void onBackupOpened(MainActivity a, android.net.Uri uri) {
        String text;
        try (java.io.InputStream in = a.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("no stream");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 256 * 1024) throw new java.io.IOException("文件太大");
            }
            text = bos.toString("UTF-8");
        } catch (Exception e) {
            Toast.makeText(a, "读取失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        SettingsPage p = filePage != null && filePage.a == a ? filePage : new SettingsPage(a);
        filePage = null;
        p.importText(text, "从文件恢复");
    }

    /** Import (241): every value checked, snapshot first (406), then settings reopen with the new values. */
    private void importSettings() {
        String clip = "";
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm.getPrimaryClip();
            if (d != null && d.getItemCount() > 0 && d.getItemAt(0).getText() != null) clip = d.getItemAt(0).getText().toString();
        } catch (Exception ignored) {}
        importText(clip, "从剪贴板恢复");
    }

    /**
     * Import from the clipboard or a file (241/402); says when / by which version it was made (408) and,
     * before anything changes, which settings it would change (404).
     */
    private void importText(final String text, final String what) {
        if (text == null || text.isEmpty() || !looksLikeBackup(text)) { toastShort("没有找到 " + a.getString(R.string.app_name) + " 的设置备份"); return; }
        java.util.List<String[]> diff = Prefs.importDiff(a, text);
        if (diff == null) { toastShort("没有找到 " + a.getString(R.string.app_name) + " 的设置备份"); return; }
        final String local = Prefs.backupLocal(text);
        if (diff.isEmpty() && local == null) { toastShort("备份里的设置和现在完全一样"); return; }
        String made = backupMade(text);
        StringBuilder m = new StringBuilder();
        if (!made.isEmpty()) m.append("备份于 ").append(made).append("\n\n");
        if (diff.isEmpty()) m.append("设置和现在一样。\n");
        else {
            m.append("会改 ").append(diff.size()).append(" 项：\n");
            for (int i = 0; i < Math.min(25, diff.size()); i++) {
                String[] d = diff.get(i);
                m.append("· ").append(d[1]).append("：").append(d[2]).append(" → ").append(d[3]).append('\n');
            }
            if (diff.size() > 25) m.append("……还有 ").append(diff.size() - 25).append(" 项\n");
        }
        if (local != null) m.append("\n另含本地隐藏的视频和观看进度，会合并到本机（不会删掉现有的）。\n");
        m.append("\n恢复后 10 秒内可在设置首页撤销设置的改动。");
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("从备份恢复？")
                .setMessage(m.toString())
                .setPositiveButton("恢复", (d, w) -> applyImport(text, what, local))
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyImport(String text, String what, String local) {
        String made = backupMade(text);
        Prefs.saveSnapshot(a);
        PrefsModel.ImportResult r = Prefs.importJson(a, text);
        if (r == null) { toastShort("没有找到 " + a.getString(R.string.app_name) + " 的设置备份"); return; }
        a.applySettings();
        if (local != null) MainActivity.js("window.ytl&&ytl.importLocal&&ytl.importLocal(" + org.json.JSONObject.quote(local) + ")");
        Toast.makeText(a, "已恢复 " + r.applied + " 项设置" + (r.ignored > 0 ? "，已忽略 " + r.ignored + " 项（取值不支持）" : "")
                + (local != null ? "，本地数据已合并" : "") + (made.isEmpty() ? "" : "\n备份于 " + made), Toast.LENGTH_LONG).show();
        offerUndo(what);
    }

    /** "2026-10-24 21:30 · v1.7.0" from a v2 backup's header; "" for older ones (408). */
    private static String backupMade(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json.trim());
            long at = o.optLong("exportedAt", 0);
            String v = o.optString("appVersion", "");
            String when = at > 0 ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(at)) : "";
            if (when.isEmpty() && v.isEmpty()) return "";
            return when + (v.isEmpty() ? "" : (when.isEmpty() ? "" : " · ") + "v" + v);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean looksLikeBackup(String s) {
        return s.contains("\"app\"") && (s.contains("YiWei") || s.contains("YT Lite"));
    }

    private void offerUndo(String what) {
        undoWhat = what;
        undoUntil = System.currentTimeMillis() + 10_000L;
        reopen();
    }

    private void confirmDefaults() {
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("恢复默认设置？")
                .setMessage("会恢复：所有开关和选项回到默认值。\n\n不会动：屏蔽的频道和关键词、登录、观看记录、本地隐藏的视频、日志。\n\n恢复后 10 秒内可在设置首页撤销。")
                .setPositiveButton("恢复默认", (d, w) -> {
                    Prefs.saveSnapshot(a);
                    Prefs.restoreDefaults(a);
                    a.applySettings();
                    toastShort("已恢复默认");
                    offerUndo("恢复默认");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyReport() { copyReport(a); }

    /** Problem report (139); also offered by the self-check bar (254). Block lists only as counts (413). */
    public static void copyReport(MainActivity a) {
        String version = Updater.installedVersion(a);
        StringBuilder b = new StringBuilder();
        b.append(Diag.header(version));
        String crash = Diag.lastCrash(a);   // newest crash on top (251)
        if (!crash.isEmpty()) b.append("—— 最近一次崩溃 ——\n").append(crash.length() > 2500 ? crash.substring(0, 2500) : crash).append("\n\n");
        b.append("WebView ").append(MainActivity.webViewVersion(a)).append(" · ").append(a.refreshRateLabel()).append('\n');
        b.append("电池优化豁免：").append(a.isIgnoringBattery()).append(" · 省电模式：").append(Prefs.powerSaving(a))
                .append(" · 远程规则：").append(RemoteRules.status(a)).append('\n');
        b.append("通知：").append(a.notificationsOk() ? "正常" : "已关闭").append(" · 后台前台服务被拒：").append(a.fgsDeniedAt() > 0).append('\n');
        b.append("设置：").append(reportSettings(a, version)).append('\n');
        b.append("今日拦截：").append(com.google.android.youtube.pro.webview.AdBlocker.report()).append("\n\n");   // (281)
        String diag = Diag.read(a, Diag.DIAG);
        String[] lines = diag.split("\n");
        b.append("—— 最近诊断日志 ——\n");
        for (int i = Math.max(0, lines.length - 80); i < lines.length; i++) b.append(lines[i]).append('\n');
        String ads = Diag.read(a, Diag.ADS);
        if (!ads.isEmpty()) {
            String[] al = ads.split("\n");
            b.append("—— 漏网广告 ——\n");
            for (int i = Math.max(0, al.length - 30); i < al.length; i++) b.append(al[i]).append('\n');
        }
        copy(a, a.getString(R.string.app_name) + " 问题报告", b.toString());
        Toast.makeText(a, "已复制问题报告，粘贴发给我即可", Toast.LENGTH_SHORT).show();
    }

    private static String reportSettings(MainActivity a, String version) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(Prefs.exportJson(a, version));
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); m.put(k, o.opt(k)); }
            return new org.json.JSONObject(PrefsModel.redactForReport(m)).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void copyLog(String file, String label) {
        String body = Diag.read(a, file);
        if (body.isEmpty()) { toastShort(label + "为空"); return; }
        copy(a, a.getString(R.string.app_name) + " " + label, Diag.header(version()) + body);
        toastShort("已复制" + label);
    }

    /** Clipboard copy marked sensitive (407): Android 13+ hides the preview of backups and reports. */
    private static void copy(Context a, String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(label, text);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.os.PersistableBundle ex = new android.os.PersistableBundle();
                ex.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(ex);
            }
            cm.setPrimaryClip(clip);
        } catch (Exception e) {
            Toast.makeText(a, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void toastShort(String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }

    private String version() {
        return Updater.installedVersion(a);
    }

    private int dp(int v) {
        return Ui.dp(a, v);
    }
}
