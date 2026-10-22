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

    public void show() {
        page("YiWei 设置", box -> {
            String ready = Updater.readyTag(a);
            if (ready != null) {
                LinearLayout card = card(box);
                card.addView(row("发现新版本 " + ready, "点此下载并安装（不会自动弹窗打扰）", v -> Updater.checkNow(a)));
            }
            LinearLayout c1 = card(box);
            c1.addView(nav("播放", "画质、速度、字幕、音轨、SponsorBlock", this::playPage));
            c1.addView(nav("睡眠与续播", "睡眠定时、淡出、一键睡眠模式、接下来播放", this::sleepPage));
            c1.addView(nav("首页与列表", "启动页、过滤、已看完、刷新、布局、搜索", this::listPage));
            c1.addView(nav("外观", "纯黑主题、强调色、卡片样式、字体", this::lookPage));
            c1.addView(nav("通知与操作", "通知栏按钮、耳机、长按菜单、触感", this::controlPage));
            LinearLayout c2 = card(box);
            c2.addView(nav("后台播放", "电池优化、ColorOS 自启动（熄屏续播必需）", this::backgroundPage));
            c2.addView(nav("隐私与记录", "观看/搜索记录、本地隐藏和屏蔽", this::privacyPage));
            c2.addView(nav("诊断与反馈", "一键问题报告、漏网广告记录", this::diagPage));
            c2.addView(nav("更新", "检查更新、下载线路、远程规则", this::updatePage));
            c2.addView(nav("关于", "版本、WebView、刷新率、本机观看统计、开源许可", this::aboutPage));
            LinearLayout c3 = card(box);
            c3.addView(row("备份设置到剪贴板", "导出为一段文字，可存到备忘录", v -> exportSettings()));
            c3.addView(row("从剪贴板恢复设置", "粘贴之前备份的那段文字后点这里", v -> importSettings()));
            c3.addView(row("恢复默认设置", "所有开关回到默认（不影响登录和观看记录）", v -> confirmDefaults()));
        });
    }

    // ===================== pages =====================

    private void playPage() {
        page("播放", box -> {
            LinearLayout c = card(box);
            c.addView(choice("默认画质", "每个视频开始时设一次，之后可手动改", Prefs.QUALITY,
                    new String[]{"自动（YouTube 决定）", "Wi-Fi 最高，流量 720p", "1080p", "720p", "480p"},
                    new String[]{"auto", "wifiMax", "1080", "720", "480"}, "auto"));
            c.addView(toggle("后台/熄屏时最低画质", "省电省流量；回到前台恢复默认画质", Prefs.LOW_QUALITY_BG));
            c.addView(toggle("省电模式时降画质、关动画", "系统开启省电模式时画质最高 480p", Prefs.POWER_SAVE_AUTO));
            c.addView(toggle("记住播放速度", "下一个视频沿用上次选的速度（最高 2 倍）", Prefs.REMEMBER_RATE));
            c.addView(toggle("优先原声音轨", "有多条音轨（AI 自动配音）时切回原声", Prefs.ORIG_AUDIO));
            c.addView(toggle("视频结束不显示推荐墙", "只隐藏片尾推荐网格，自动续播倒计时照常", Prefs.NO_END_WALL));
            c.addView(toggle("音量均衡（实验）", "压缩忽大忽小的音量；若关屏后没声音请关闭", Prefs.NORMALIZE));
            LinearLayout s = card(box, "字幕");
            s.addView(choice("默认字幕", "跟随 YouTube 时不做任何改动", Prefs.CAPTIONS,
                    new String[]{"跟随 YouTube", "默认关闭", "有中文字幕时打开"}, new String[]{"yt", "off", "zh"}, "yt"));
            s.addView(choice("字幕大小", "只改显示大小，不影响字幕内容", Prefs.SUB_SIZE,
                    new String[]{"默认", "小", "大", "特大"}, new String[]{"default", "s", "l", "xl"}, "default"));
            s.addView(choice("字幕背景", "夜里看可选半透明或无背景", Prefs.SUB_BG,
                    new String[]{"默认", "半透明", "无背景（文字描边）"}, new String[]{"default", "half", "none"}, "default"));
            LinearLayout sb = card(box, "SponsorBlock");
            sb.addView(toggle("自动跳过赞助片段", "跳过后 2 秒内可点「撤销」回看", Prefs.SPONSOR_BLOCK));
            sb.addView(dyn("跳过类别", () -> Prefs.sponsorCatsLabel(a) + " · 从下一个视频开始生效", v -> pickSponsorCats()));
        });
    }

    private void sleepPage() {
        page("睡眠与续播", box -> {
            LinearLayout c = card(box, "睡眠定时");
            c.addView(dyn("一键睡眠模式", () -> "定时 " + SleepTimer.optionLabel(Prefs.sleepDefault(a)) + " + 最低画质 + 只续播同频道 + 调暗屏幕",
                    v -> { a.startSleepMode(); toastShort("已开启睡眠模式"); }));
            c.addView(dyn("现在开始 / 关闭", () -> SleepTimer.active() ? "正在定时：" + SleepTimer.optionLabel(SleepTimer.mode()) + "（点此关闭）" : "未开启",
                    v -> {
                        if (SleepTimer.active()) { SleepTimer.cancel(a); refreshAll(); }
                        else PlayerTools.pickSleep(a, "现在开始睡眠定时", m -> { SleepTimer.start(a, m); refreshAll(); });
                    }));
            c.addView(dyn("默认时长", () -> SleepTimer.optionLabel(Prefs.sleepDefault(a)) + " · 通知栏按钮和一键睡眠模式用",
                    v -> PlayerTools.pickSleep(a, "默认时长", m -> { Prefs.put(a, Prefs.SLEEP_DEFAULT, m); refreshAll(); })));
            c.addView(choiceInt("淡出时长", "到点前音量逐渐变小的时间", Prefs.SLEEP_FADE,
                    new String[]{"10 秒", "30 秒", "60 秒"}, new int[]{10, 30, 60}, 60));
            c.addView(toggle("开启的时长记为默认", "下次在通知栏一键开启同样的时长", Prefs.SLEEP_REMEMBER));
            c.addView(toggle("播放器角上显示剩余时间", "左上角小字，不挡画面", Prefs.SLEEP_BADGE));
            c.addView(toggle("夜间打开时提示开定时", "22:00–5:00 打开 App 时提示，每晚最多一次", Prefs.NIGHT_PROMPT));
            LinearLayout n = card(box, "续播");
            n.addView(choice("接下来播放", "默认用 YouTube 自动播放；其他选项会关掉它由本 App 选", Prefs.NEXT_SOURCE,
                    new String[]{"YouTube 推荐（默认）", "优先同频道", "只在播放列表内"}, new String[]{"rec", "channel", "playlist"}, "rec"));
            n.addView(toggle("睡眠定时期间只续播同频道/列表", "睡着时不跳到刺激内容；找不到时照常推荐", Prefs.SLEEP_CALM));
            n.addView(toggle("未登录时记住观看进度", "只存在本机，再打开同一视频从上次位置继续", Prefs.RESUME_LOCAL));
            n.addView(toggle("重开时提示继续上次视频", "被系统清掉后打开 App，底部提示一键回到断点", Prefs.RESUME_PROMPT));
        });
    }

    private void listPage() {
        page("首页与列表", box -> {
            LinearLayout h = card(box, "首页");
            h.addView(choice("打开 App 时进入", "从桌面图标启动时的第一页", Prefs.START_PAGE,
                    new String[]{"首页", "订阅"}, new String[]{"home", "subs"}, "home"));
            h.addView(toggle("隐藏已看完的视频", "刷新更容易出新视频；看了一部分的保留", Prefs.HIDE_WATCHED));
            h.addView(choiceInt("「已看完」的标准", "看到多少算看完", Prefs.SEEN_MIN,
                    new String[]{"看过 50%", "看过 80%（默认）", "看过 95%"}, new int[]{50, 80, 95}, 80));
            h.addView(toggle("刷新后提示新视频数", "下拉刷新后提示「更新了 N 个新视频」", Prefs.REFRESH_COUNT));
            h.addView(toggle("隐藏首页货架", "新闻、合集、「可能喜欢」等横向模块", Prefs.HIDE_SHELVES));
            LinearLayout f = card(box, "过滤（首页和「接下来播放」）");
            f.addView(dyn("屏蔽的频道", () -> countLabel(Prefs.BLOCK_CHANNELS, "长按视频卡片 →「不再显示此频道」也能加"),
                    v -> editLines("屏蔽的频道", "每行一个频道名（和卡片上显示的一致）", Prefs.BLOCK_CHANNELS)));
            f.addView(dyn("屏蔽的关键词", () -> countLabel(Prefs.BLOCK_WORDS, "标题含这些词就不显示，搜索结果也生效"),
                    v -> editLines("屏蔽的关键词", "每行一个词，不区分大小写", Prefs.BLOCK_WORDS)));
            f.addView(choiceInt("隐藏短视频", "按卡片上的时长判断", Prefs.MIN_DUR,
                    new String[]{"不隐藏", "短于 1 分钟", "短于 3 分钟", "短于 5 分钟", "短于 10 分钟"}, new int[]{0, 1, 3, 5, 10}, 0));
            f.addView(choiceInt("隐藏旧视频", "按「x 天前」这类发布时间判断", Prefs.MAX_AGE,
                    new String[]{"不隐藏", "超过 7 天", "超过 30 天", "超过半年", "超过 1 年"}, new int[]{0, 7, 30, 180, 365}, 0));
            f.addView(toggle("隐藏直播、首映和即将开播", "只在首页和「接下来播放」里生效", Prefs.HIDE_LIVE));
            LinearLayout s = card(box, "订阅与搜索");
            s.addView(toggle("订阅页只显示视频", "隐藏社区帖子", Prefs.SUBS_ONLY_VIDEOS));
            s.addView(toggle("搜索结果隐藏「相关搜索」等", "「人们还看了」「为你推荐」这类插入模块", Prefs.HIDE_SEARCH_EXTRAS));
            s.addView(toggle("搜索结果快捷筛选", "顶部一排：最新、只看视频、本周、按时长", Prefs.SEARCH_CHIPS));
            s.addView(toggle("打开搜索时弹出键盘", "搜索框出现时自动聚焦", Prefs.SEARCH_FOCUS));
            LinearLayout l = card(box, "布局与流畅度");
            l.addView(choice("首页列表布局", "紧凑：小图在左、标题在右，一屏看更多", Prefs.COMPACT + "_choice",
                    new String[]{"大图（默认）", "紧凑列表"}, new String[]{"big", "compact"}, "big"));
            l.addView(toggle("返回列表时保持位置", "从视频返回时回到刚才滑到的地方", Prefs.KEEP_SCROLL));
            l.addView(toggle("列表渲染优化", "屏幕外的卡片不渲染、去掉骨架闪烁；排版异常时可关闭", Prefs.PERF_CSS));
        });
    }

    private void lookPage() {
        page("外观", box -> {
            LinearLayout c = card(box);
            c.addView(toggle("纯黑主题（AMOLED）", "深色模式下背景纯黑，更省电、夜里不刺眼", Prefs.AMOLED));
            c.addView(choice("强调色", "进度条、按钮和开关的颜色", Prefs.ACCENT,
                    new String[]{"默认（YouTube 红 / 蓝）", "蓝", "青", "紫", "橙", "粉"},
                    new String[]{"", "#2F6BFF", "#00BFA5", "#9C6BFF", "#FF8A00", "#FF4F8B"}, ""));
            c.addView(toggle("统一卡片样式", "缩略图圆角统一、标题两行、时长和进度条更醒目", Prefs.CARD_STYLE));
            c.addView(toggle("深色模式压暗缩略图", "夜里看列表不刺眼", Prefs.DIM_THUMBS));
            c.addView(toggle("网页字体跟随系统大小", "使用系统字体缩放（限制在 85%–130%）", Prefs.FONT_FOLLOW));
        });
    }

    private void controlPage() {
        page("通知与操作", box -> {
            LinearLayout c = card(box, "通知栏与耳机");
            c.addView(toggle("通知栏用快退/快进", "把「上一个/下一个」换成「快退 10 秒/快进 30 秒」", Prefs.NOTIF_SEEK));
            c.addView(toggle("通知栏「稍后观看」按钮", "听到好内容一键收藏（需登录；睡眠定时中让位给 +15 分钟）", Prefs.NOTIF_WL));
            c.addView(toggle("耳机按键多击", "按一下暂停/播放，两下下一个，三下上一个", Prefs.HEADSET_MULTI));
            LinearLayout t = card(box, "触控");
            t.addView(toggle("长按视频卡片快捷菜单", "稍后观看、不再显示此频道、本地隐藏等", Prefs.LONG_PRESS));
            t.addView(toggle("触感反馈", "开定时、刷新完成、长按菜单时轻震一下", Prefs.HAPTIC));
        });
    }

    private void backgroundPage() {
        page("后台播放", box -> {
            LinearLayout c = card(box);
            c.addView(dyn("电池优化", () -> a.isIgnoringBattery() ? "已不受限制 ✓" : "点此关闭电池优化（熄屏持续播放必需）", v -> a.requestIgnoreBattery()));
            c.addView(row("自启动 / 后台运行（ColorOS 等）", "打开系统的自启动管理；找不到时打开应用详情", v -> a.openAutostartSettings()));
            c.addView(row("应用详情", "耗电管理 → 允许后台运行", v -> a.openAppDetails()));
        });
    }

    private void privacyPage() {
        page("隐私与记录", box -> {
            LinearLayout c = card(box);
            c.addView(row("观看记录和搜索记录", "打开 YouTube 的记录页，可暂停或清除（需登录）", v -> { closeAll(); a.loadInApp("https://m.youtube.com/feed/history"); }));
            c.addView(row("恢复本地隐藏的视频", "长按菜单里「本地隐藏」过的视频重新显示", v -> {
                MainActivity.js("ytl.unhideAll()");
                toastShort("已恢复");
            }));
            c.addView(dyn("屏蔽的频道", () -> countLabel(Prefs.BLOCK_CHANNELS, "只存在本机"),
                    v -> editLines("屏蔽的频道", "每行一个频道名", Prefs.BLOCK_CHANNELS)));
            c.addView(row("说明", "设置、日志和本地进度只存在本机；登录信息只在 YouTube 页面里", null));
        });
    }

    private void diagPage() {
        page("诊断与反馈", box -> {
            LinearLayout c = card(box);
            c.addView(row("复制问题报告", "版本、WebView、设备、设置摘要和最近日志，粘贴发给我", v -> copyReport()));
            c.addView(dyn("复制漏网广告记录", () -> Diag.lines(a, Diag.ADS) + " 条", v -> copyLog(Diag.ADS, "漏网广告记录")));
            c.addView(dyn("复制诊断日志", () -> Diag.lines(a, Diag.DIAG) + " 行", v -> copyLog(Diag.DIAG, "诊断日志")));
            c.addView(row("清空以上记录", "只清本机日志", v -> {
                Diag.clear(a, Diag.ADS);
                Diag.clear(a, Diag.DIAG);
                refreshAll();
                toastShort("已清空");
            }));
        });
    }

    private void updatePage() {
        page("更新", box -> {
            LinearLayout c = card(box);
            c.addView(dyn("检查更新", () -> {
                String r = Updater.readyTag(a);
                return r != null ? "有新版本 " + r + "，点此更新" : "当前 " + version() + " · 每天自动检查一次（有新版只显示小红点）";
            }, v -> Updater.checkNow(a)));
            c.addView(toggle("国内网络用镜像下载", "下载慢时开启；安装包都会做 SHA-256 校验，失败自动改回直连", Prefs.UPDATE_MIRROR));
            LinearLayout r = card(box, "远程规则");
            r.addView(toggle("自动更新屏蔽规则", "只从本 App 的 GitHub 仓库拉取并校验，失败用内置规则", Prefs.REMOTE_RULES));
            r.addView(dyn("规则状态", () -> RemoteRules.status(a) + " · 点此立即检查", v -> RemoteRules.checkNow(a, () -> refreshAll())));
        });
    }

    private void aboutPage() {
        page("关于", box -> {
            LinearLayout c = card(box);
            c.addView(row("YiWei " + version(), "个人自用的精简 YouTube 客户端 · 包名 " + a.getPackageName(), null));
            c.addView(row("WebView", MainActivity.webViewVersion(a), null));
            c.addView(row("屏幕刷新率", a.refreshRateLabel(), null));
            c.addView(dyn("本机观看统计", () -> "近 7 天 " + fmtDuration(PlaybackService.weekSeconds(a)) + " · 今天 " + fmtDuration(PlaybackService.todaySeconds(a)), null));
            c.addView(row("开源许可", "基于 YTPro（MIT）· AndroidX（Apache 2.0）· SponsorBlock 数据（CC BY-NC-SA 4.0）", v -> licenses()));
        });
    }

    // ===================== page frame =====================

    private interface Body { void build(LinearLayout box); }

    private final List<Dialog> open = new ArrayList<>();

    private void page(String title, Body body) {
        final Dialog d = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(16), dp(4));
        ImageButton back = new ImageButton(a);
        back.setImageResource(R.drawable.ic_arrow_back_white);
        back.setBackground(null);
        Ui.ripple(back);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> d.dismiss());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = Ui.text(a, title, 20, Ui.TEXT);
        t.setPadding(dp(8), 0, 0, 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        ScrollView scroll = new ScrollView(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, dp(24));
        scroll.addView(box);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        body.build(box);

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setWindowAnimations(android.R.style.Animation_Activity);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                w.setStatusBarColor(Ui.BG);
                w.setNavigationBarColor(Ui.BG);
            }
        }
        d.setOnDismissListener(x -> { open.remove(d); refreshAll(); });
        open.add(d);
        d.show();
    }

    private void closeAll() {
        for (Dialog d : new ArrayList<>(open)) { try { d.dismiss(); } catch (Exception ignored) {} }
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

    private View nav(String title, String sub, final Runnable open) {
        LinearLayout l = baseRow();
        l.addView(texts(title, Ui.text(a, sub, 13, Ui.TEXT_2)), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView chev = Ui.text(a, "›", 22, Ui.TEXT_2);
        l.addView(chev);
        Ui.ripple(l);
        l.setOnClickListener(v -> open.run());
        l.setContentDescription(title + "，" + sub);
        return l;
    }

    private View toggle(String title, String sub, final String key) {
        LinearLayout l = baseRow();
        l.addView(texts(title, Ui.text(a, sub, 13, Ui.TEXT_2)), new LinearLayout.LayoutParams(0, -2, 1f));
        final Switch sw = new Switch(a);
        sw.setChecked(Prefs.on(a, key));
        tint(sw);
        sw.setOnCheckedChangeListener((b, checked) -> {
            Prefs.put(a, key, checked);
            applied();
        });
        sw.setContentDescription(title);
        l.addView(sw);
        Ui.ripple(l);
        l.setOnClickListener(v -> sw.toggle());
        return l;
    }

    private void tint(Switch sw) {
        try {
            int acc = Ui.accent(a);
            int[][] states = {{android.R.attr.state_checked}, {}};
            sw.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{acc, 0xFFBDBDBD}));
            sw.setTrackTintList(new android.content.res.ColorStateList(states, new int[]{(acc & 0x00FFFFFF) | 0x80000000, 0x40FFFFFF}));
        } catch (Throwable ignored) {}
    }

    private View choice(final String title, final String note, final String key, final String[] labels, final String[] values, final String def) {
        final Label lab = () -> {
            String cur = currentStr(key, def);
            String label = labels[0];
            for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) label = labels[i];
            return note == null ? label : label + " · " + note;
        };
        final View[] self = new View[1];
        self[0] = dyn(title, lab, v -> {
            String cur = currentStr(key, def);
            int sel = 0;
            for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) sel = i;
            new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, sel, (d, which) -> {
                        putStr(key, values[which]);
                        applied();
                        d.dismiss();
                    })
                    .show();
        });
        return self[0];
    }

    // the layout choice is stored as the COMPACT boolean
    private String currentStr(String key, String def) {
        if ((Prefs.COMPACT + "_choice").equals(key)) return Prefs.on(a, Prefs.COMPACT) ? "compact" : "big";
        return Prefs.str(a, key, def);
    }

    private void putStr(String key, String v) {
        if ((Prefs.COMPACT + "_choice").equals(key)) Prefs.put(a, Prefs.COMPACT, "compact".equals(v));
        else Prefs.put(a, key, v);
    }

    private View choiceInt(final String title, final String note, final String key, final String[] labels, final int[] values, final int def) {
        final Label lab = () -> {
            int cur = Prefs.get(a).getInt(key, def);
            String label = String.valueOf(cur);
            for (int i = 0; i < values.length; i++) if (values[i] == cur) label = labels[i];
            return note == null ? label : label + " · " + note;
        };
        return dyn(title, lab, v -> {
            int cur = Prefs.get(a).getInt(key, def);
            int sel = 0;
            for (int i = 0; i < values.length; i++) if (values[i] == cur) sel = i;
            new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, sel, (d, which) -> {
                        Prefs.put(a, key, values[which]);
                        applied();
                        d.dismiss();
                    })
                    .show();
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
        e.setHintTextColor(0xFF777777);
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

    private void pickSponsorCats() {
        final String[] keys = Prefs.SB_CATS;
        final List<String> cur = Prefs.sponsorCats(a);
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
                    refreshAll();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void exportSettings() {
        copy("YiWei 设置备份", Prefs.exportJson(a));
        toastShort("已复制设置备份");
    }

    private void importSettings() {
        String clip = "";
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm.getPrimaryClip();
            if (d != null && d.getItemCount() > 0 && d.getItemAt(0).getText() != null) clip = d.getItemAt(0).getText().toString();
        } catch (Exception ignored) {}
        int n = clip.isEmpty() ? -1 : Prefs.importJson(a, clip);
        if (n < 0) { toastShort("剪贴板里没有 YiWei 的设置备份"); return; }
        a.applySettings();
        refreshAll();
        Toast.makeText(a, "已恢复 " + n + " 项设置（部分页面需重新打开设置才显示新值）", Toast.LENGTH_LONG).show();
        closeAll();
    }

    private void confirmDefaults() {
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("恢复默认设置？")
                .setMessage("所有开关和选项回到默认值。登录、观看记录、日志和屏蔽列表以外的本机数据不受影响。\n\n建议先「备份设置到剪贴板」。")
                .setPositiveButton("恢复默认", (d, w) -> {
                    Prefs.restoreDefaults(a);
                    a.applySettings();
                    toastShort("已恢复默认");
                    closeAll();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyReport() {
        StringBuilder b = new StringBuilder();
        b.append(Diag.header(version()));
        b.append("WebView ").append(MainActivity.webViewVersion(a)).append(" · ").append(a.refreshRateLabel()).append('\n');
        b.append("电池优化豁免：").append(a.isIgnoringBattery()).append(" · 省电模式：").append(Prefs.powerSaving(a))
                .append(" · 远程规则：").append(RemoteRules.status(a)).append('\n');
        b.append("设置：").append(Prefs.exportJson(a).replace("\n", "")).append("\n\n");
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
        copy("YiWei 问题报告", b.toString());
        toastShort("已复制问题报告，粘贴发给我即可");
    }

    private void copyLog(String file, String label) {
        String body = Diag.read(a, file);
        if (body.isEmpty()) { toastShort(label + "为空"); return; }
        copy("YiWei " + label, Diag.header(version()) + body);
        toastShort("已复制" + label);
    }

    private void copy(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
        } catch (Exception e) {
            toastShort("复制失败");
        }
    }

    private void licenses() {
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("开源许可")
                .setMessage("YTPro — Copyright (c) Prateek Chaubey, MIT License。YiWei 在其基础上精简改写，同样以 MIT 许可发布。\n\n"
                        + "AndroidX（webkit、swiperefreshlayout、core）— Apache License 2.0。\n\n"
                        + "SponsorBlock 片段数据来自 sponsor.ajay.app，CC BY-NC-SA 4.0。\n\n"
                        + "YouTube 是 Google LLC 的商标；本应用与 Google 无关，仅供个人使用。")
                .setPositiveButton("好", null)
                .show();
    }

    private static String fmtDuration(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60;
        return h > 0 ? h + " 小时 " + m + " 分" : m + " 分钟";
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
