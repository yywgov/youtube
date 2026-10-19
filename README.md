# youtube · YT Lite

一个精简、流畅、无广告的 YouTube 安卓客户端（个人自用）。基于 [prateek-chaubey/YTPro](https://github.com/prateek-chaubey/YTPro)（MIT）改造：
砍掉了大部分附加功能，只保留「看视频 / 听视频」真正需要的部分，重点优化 **流畅度**、**去广告** 和 **熄屏听着入睡**。

本质上它是一个内嵌 `m.youtube.com` 的 WebView，加上一段打包在 APK 里的精简脚本（`app/src/main/assets/ytlite.js`，不会从任何 CDN 下载脚本）。

## 功能

**去广告**
- 在 WebView 网络层直接拦截广告 / 跟踪 / 遥测请求（doubleclick、googleads、googlesyndication、`/pagead/`、`/ptracking`、`/api/stats/ads`、`log_event` 等）
- 保留 `watchtime` / `playback` 统计，观看记录和续播进度不受影响
- 从播放器数据中剔除广告字段（`adPlacements` / `playerAds` / `adSlots`），漏网的视频广告自动跳过
- 用 CSS 隐藏首页推广、横幅和「在 App 中打开」

**熄屏收听 / 助眠**
- 后台、熄屏持续播放（前台服务 `mediaPlayback` + 部分唤醒锁 + Wi‑Fi 锁，播放时才持有）
- 锁屏 / 通知栏 / 蓝牙 / 有线耳机按键：播放、暂停、上一个、下一个、拖动进度
- **睡眠定时**：15 / 30 / 45 / 60 / 90 分钟或「当前视频结束时」；最后 60 秒音量渐弱再暂停。通知栏按钮可循环切换，设置里可改默认时长
- 自动连播：熄屏时也会接着播下一个；自动关闭「视频已暂停，是否继续观看？」；强制打开自动播放
- 拔掉耳机自动暂停
- 断网卡住后，网络恢复时自动续播
- 后台 / 熄屏时自动切到最低画质（省电省流量，可关闭；回到前台恢复自动画质）
- 首次启动引导关闭电池优化

**界面与流畅度**
- 默认深色主题
- 隐藏视频下方的点赞 / 点踩 / 分享 / 保存 / 混剪 / 下载 / 剪辑 / 感谢 按钮行（评论保留）
- 首页下拉刷新；在首页时再点「首页」也会刷新推荐
- 可选隐藏 Shorts
- SponsorBlock 自动跳过赞助、自我推广、求订阅片段（默认开启）
- 注入脚本全面重写：没有 `setInterval` 轮询，只有一个去抖的 MutationObserver；每个页面只注入一次
- 硬件加速、WebView 缓存

**登录**：在应用里直接登录 Google 账号（已处理「此浏览器或应用可能不安全」的拦截）。

设置入口：YouTube 顶栏 Logo 旁边的齿轮图标。

## 已删除的上游功能

下载器（视频 / Shorts / 封面 / 字幕）、合流、分享到下载、Gemini、画中画、音量 / 亮度手势、双指缩放和强制缩放、10 倍速、点踩数显示、收藏（爱心）、开发者模式 / eruda、从 CDN 加载脚本、上游自动更新检查。

## 下载安装

- **正式版**：在 [Releases](https://github.com/yywgov/youtube/releases) 下载 `YTLite-x.y.z.apk`（推送 `v*` 标签时自动发布）。
- **最新构建**：打开 [Actions](https://github.com/yywgov/youtube/actions)，点最新一次成功的 “Build APK”，在页面底部 Artifacts 里下载 `YTLite-apk`（需登录 GitHub，下载下来是 zip，解压得到 apk）。

包名为 `com.yywgov.ytlite`，应用名「YT Lite」，可以和官方 YouTube、上游 YTPro 同时安装。每次构建都用仓库里同一把测试签名，新版本可以直接覆盖安装。

> 签名使用的是仓库 `signer/` 里的公开测试密钥，只适合个人自用。

## 手机上需要做的设置（熄屏播放必看）

1. 首次打开时允许 **通知** 权限（Android 13+），否则看不到播放控制。
2. 允许 **不受电池优化限制**（首次启动会弹窗；也可在 设置 → 后台播放 里打开）。
3. 国产系统还需要：
   - **小米 / HyperOS**：应用详情 → 省电策略 → 无限制；开启「自启动」；最近任务里下拉加锁。
   - **OPPO / 一加 / realme（ColorOS）**：应用详情 → 耗电管理 → 允许后台运行、允许自启动。
   - **vivo / iQOO**：i 管家 → 应用管理 → 自启动；电池 → 后台耗电管理 → 允许后台高耗电。
   - **华为 / 荣耀**：电池 → 启动管理 → 关闭「自动管理」，三项全部允许。

## 自行构建

```bash
./gradlew assembleRelease   # 需要 JDK 17 和 Android SDK (compileSdk 36)
```

GitHub Actions（`.github/workflows/gradle.yml`）在每次推送到 `main` 时构建并上传 APK；推送 `v*` 标签时额外创建 Release。

## 致谢与许可

- 原项目：[YTPro](https://github.com/prateek-chaubey/YTPro) © 2022 Prateek Chaubey，MIT 许可。
- 赞助片段数据：[SponsorBlock](https://sponsor.ajay.app/)。
- 本项目同样以 MIT 许可发布，见 [LICENSE](LICENSE)（保留原作者版权声明）。
- 与 Google / YouTube 无关，仅供个人学习使用。
