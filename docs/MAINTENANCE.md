# 一维视频 YiWei 维护说明

给以后改这个项目的人（包括自动维护任务）看的仓库内说明（433）。不含任何密钥或个人环境信息。

## 结构
- `app/src/main/assets/ytlite.js`：注入到 m.youtube.com 的页面脚本（去广告钩子、Shorts/反馈/按钮行隐藏、续播、SponsorBlock、睡眠淡出、列表过滤等）。
  **它是构建产物**：源码在 `src/js/NN-名字.js`，按文件名顺序逐字拼接（仍是一个 IIFE）。改 `src/js` 后运行 `node tools/build-js.js`；CI 用 `--check` 核对两者一致。
- Java：`app/src/main/java/com/google/android/youtube/pro/`
  - `MainActivity` 生命周期与组装；`FrameRate` 高刷/帧率；`Links` 外部链接解析；`SettingsPage` 原生设置页（含设置搜索）；`PlayerTools` 月亮按钮播放工具；`PlaybackService` 前台服务、媒体会话、通知；`SleepTimer` 睡眠定时；`Updater` 一键更新；`RemoteRules` 远程规则；`Prefs`/`PrefsModel` 设置；`Logic` 纯 Java 小函数（有 JUnit）；`Ui` 设计 token 与底部弹层（颜色只能写在这里）。
  - `webview/`：`AdBlocker` 网络拦截、`WatchPageFilter` 观看页流式改写、`WebAppInterface` JS 桥。
- `rules/rules.json`：远程规则（额外隐藏选择器、广告字样、拦截路径、搜索快捷筛选）。改动后必须 `sha256sum rules.json | cut -d' ' -f1 > rules.json.sha256` 并把 `version` 加 1，CI 会检查。

## 不能动的东西
- `applicationId com.yywgov.ytlite`、`signer/` 下的签名、仓库名：动了就无法覆盖升级。
- SharedPreferences 的 key 一个都不改名；需要改含义时用 `Prefs.migrate` 里的幂等迁移。新增设置 key 必须同时登记到 `PrefsModel` 三组白名单和 `tests/js/batch4.test.js` 的 ADDED 表（契约测试会拦）。
- 备份 JSON 的 `app` 字段保持 `"YiWei"`；UA、媒体会话标签、诊断头里的 ASCII 名 `YiWei` 也不改。显示名用 `R.string.app_name`（一维视频）。
- 页面脚本调用的桥方法必须在 `WebAppInterface` 里有（契约测试 + CI 用 dexdump 核对发布包）。
- 不加回：下载器、Gemini/AI、画中画、亮度/音量手势、缩放、10 倍速、踩数、小红心、开发者模式、Shorts、点赞/分享按钮行、反馈/帮助/举报入口。

## 测试
- JS：`cd tests/js && npm ci && npm test`（node --test + jsdom，测试经 `window.ytl._` 访问内部函数）；`npm run coverage` 打印覆盖率摘要。
- Java：`tools/junit.sh path/to/android.jar` 本地跑 `app/src/test` 的 JUnit（只测纯 Java 类）；CI 跑 `./gradlew testReleaseUnitTest`。
- 文案：`node tools/lint-copy.js`（中英文间空格、全角标点、省略号；只警告）。
- CI（`.github/workflows/gradle.yml`）顺序：规则检查 → 页面脚本一致性 → 文案检查 → JS 测试 + 覆盖率 → 压缩脚本并对压缩版再跑测试 → JUnit + 构建 → 发布包桥方法检查 → 签名（文件名取自 Gradle 写出的 `app/build/version.txt`，tag 构建必须与 tag 一致）→ tag 时发 Release。

## 发版
1. main 上测试全过、CI 通过。
2. 写中文更新说明（会成为 tag 注释，也是 App 内「本次更新」的内容）。
3. `tools/release.sh X.Y.Z notes.txt`：打注释 tag、推送、等 Release 出现 `YiWei-X.Y.Z.apk`（及 `.sha256`），再把 `app/build.gradle` 的 `baseVersion` 调到下一个次版本并推送。
4. App 内「检查更新」读 GitHub 最新 Release，按 versionName 比较；安装包用 GitHub digest 或 `.sha256` 校验，并比对签名证书。

## 依赖
`.github/dependabot.yml` 每月为 Gradle、tests/js 的 npm、GitHub Actions 开更新 PR，走同一套 CI，不自动合并。
