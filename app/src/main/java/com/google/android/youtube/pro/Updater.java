package com.google.android.youtube.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-app updates from GitHub Releases.
 * Compares the release tag (v1.2.3) with the installed versionName (CI sets it from
 * the tag; main builds are "x.y.z-dev.N", which count as older than x.y.z), downloads
 * the .apk asset into the app cache with progress, then hands it to the system
 * installer through a FileProvider. The "install unknown apps" permission (Android 8+)
 * is requested on the way and the install resumes when the user comes back.
 */
public final class Updater {
    private static final String API = "https://api.github.com/repos/yywgov/youtube/releases/latest";
    private static final String API_TAG = "https://api.github.com/repos/yywgov/youtube/releases/tags/";
    public static final String RELEASES = "https://github.com/yywgov/youtube/releases";
    private static final String LATEST_PAGE = "https://github.com/yywgov/youtube/releases/latest";
    /** Optional download mirror for mainland networks (87); only used when the APK hash is known. */
    private static final String MIRROR = "https://ghfast.top/";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final long AUTO_INTERVAL = 24L * 3600L * 1000L;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean busy = new AtomicBoolean(false);

    private Updater() {}

    static final class Release {
        String tag = "", version = "", name = "", notes = "", page = RELEASES, apkUrl = null, apkName = null;
        String sha256 = null, shaUrl = null, altApkUrl = null;
        long apkSize = 0;
    }

    // ===================== entry points =====================

    /** Tag of a newer release found by the daily check, or null (137). */
    public static String readyTag(Activity a) {
        String t = Prefs.str(a, Prefs.UPDATE_READY, "");
        if (t.isEmpty()) return null;
        if (compareVersions(t, installedVersion(a)) <= 0) { Prefs.put(a, Prefs.UPDATE_READY, ""); return null; }
        return t;
    }

    /** On launch: at most once a day, silent unless there is something new. */
    public static void autoCheck(MainActivity a) {
        SharedPreferences p = Prefs.get(a);
        long now = System.currentTimeMillis();
        long last = p.getLong(Prefs.LAST_UPDATE_CHECK, 0L);
        if (last > 0 && now - last < AUTO_INTERVAL && now >= last) return;
        Prefs.put(a, Prefs.LAST_UPDATE_CHECK, now);
        check(a, false);
    }

    /** Settings → 检查更新. */
    public static void checkNow(MainActivity a) {
        Toast.makeText(a, "正在检查更新…", Toast.LENGTH_SHORT).show();
        Prefs.put(a, Prefs.LAST_UPDATE_CHECK, System.currentTimeMillis());
        check(a, true);
    }

    /** onResume: finish an install that waited for the "install unknown apps" permission. */
    public static void onResume(Activity a) {
        String path = Prefs.get(a).getString(Prefs.PENDING_APK, null);
        if (path == null) return;
        File f = new File(path);
        if (!f.exists()) {
            Prefs.get(a).edit().remove(Prefs.PENDING_APK).apply();
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) return;
        Prefs.get(a).edit().remove(Prefs.PENDING_APK).apply();
        launchInstaller(a, f);
    }

    /** Delete downloaded packages once they are installed (or stale). */
    public static void cleanup(Activity a) {
        if (Prefs.get(a).getString(Prefs.PENDING_APK, null) != null) return;
        IO.execute(() -> {
            File dir = downloadDir(a);
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
                f.delete();
        });
    }

    // ===================== check =====================

    private static void check(MainActivity a, final boolean manual) {
        if (!busy.compareAndSet(false, true)) return;
        final WeakReference<MainActivity> ref = new WeakReference<>(a);
        final String installed = installedVersion(a);
        IO.execute(() -> {
            Release r = null;
            String err = null;
            try {
                r = fetchLatest();
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final Release fr = r;
            final String ferr = err;
            MAIN.post(() -> {
                busy.set(false);
                MainActivity act = ref.get();
                if (act == null || act.isFinishing()) return;
                if (fr == null) {
                    if (manual) {
                        new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                                .setTitle("检查更新失败")
                                .setMessage(ferr + "\n\n也可以到 GitHub Releases 手动下载。")
                                .setPositiveButton("打开 Releases", (d, w) -> act.openExternal(RELEASES))
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                    return;
                }
                if (compareVersions(fr.version, installed) <= 0) {
                    Prefs.put(act, Prefs.UPDATE_READY, "");
                    if (manual) Toast.makeText(act, "已是最新版本（" + installed + "）", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!fr.notes.isEmpty()) {
                    Prefs.put(act, Prefs.NOTES_FOR, fr.version);
                    Prefs.put(act, Prefs.NOTES, fr.notes.length() > 6000 ? fr.notes.substring(0, 6000) : fr.notes);
                }
                if (!manual) {
                    // no popup (137): a dot on the settings gear and a banner at the top of settings
                    Prefs.put(act, Prefs.UPDATE_READY, fr.tag);
                    MainActivity.js("ytl.cfg({updateDot:true})");
                    return;
                }
                showUpdateDialog(act, fr, installed, manual);
            });
        });
    }

    private static Release fetchLatest() throws Exception {
        try {
            return fetchApi(API);
        } catch (Exception e) {
            // API rate limit / blocked (86): read the version from the releases/latest redirect instead
            Release r = fromLatestRedirect();
            if (r != null) return r;
            throw e;
        }
    }

    private static Release fromLatestRedirect() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(LATEST_PAGE).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "YiWei-updater");
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            if (code < 300 || code >= 400 || loc == null) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("/releases/tag/(v?[0-9][0-9A-Za-z.\\-]*)$").matcher(loc);
            if (!m.find()) return null;
            Release r = new Release();
            r.tag = m.group(1);
            r.version = r.tag.startsWith("v") ? r.tag.substring(1) : r.tag;
            r.name = r.tag;
            r.page = RELEASES + "/tag/" + r.tag;
            r.apkName = "YiWei-" + r.version + ".apk";
            r.apkUrl = RELEASES + "/download/" + r.tag + "/" + r.apkName;
            r.altApkUrl = RELEASES + "/download/" + r.tag + "/YTLite-" + r.version + ".apk";
            r.shaUrl = r.apkUrl + ".sha256";
            return r;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Release fetchApi(String api) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(api).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "YiWei-updater");
            int code = c.getResponseCode();
            if (code == 404) throw new Exception("还没有发布任何版本");
            if (code == 403 || code == 429) throw new Exception("GitHub 接口访问太频繁，请稍后再试");
            if (code != 200) throw new Exception("GitHub 返回 HTTP " + code);
            JSONObject o = new JSONObject(readAll(c.getInputStream()));
            Release r = new Release();
            r.tag = o.optString("tag_name", "");
            r.version = r.tag.startsWith("v") || r.tag.startsWith("V") ? r.tag.substring(1) : r.tag;
            r.name = o.optString("name", r.tag);
            r.notes = o.optString("body", "").trim();
            r.page = o.optString("html_url", RELEASES);
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                // any .apk asset (YiWei-x.y.z.apk now, YTLite-x.y.z.apk before 1.4.0)
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject as = assets.getJSONObject(i);
                    String n = as.optString("name", "");
                    if (n.toLowerCase(Locale.ROOT).endsWith(".apk") && r.apkUrl == null) {
                        r.apkName = n;
                        r.apkUrl = as.optString("browser_download_url", null);
                        r.apkSize = as.optLong("size", 0L);
                        String dg = as.optString("digest", "");   // "sha256:<hex>" on newer GitHub releases (83)
                        if (dg.startsWith("sha256:") && dg.substring(7).matches("[0-9a-fA-F]{64}")) r.sha256 = dg.substring(7).toLowerCase(Locale.ROOT);
                    }
                }
                for (int i = 0; i < assets.length() && r.apkName != null; i++) {
                    JSONObject as = assets.getJSONObject(i);
                    if ((r.apkName + ".sha256").equals(as.optString("name", ""))) r.shaUrl = as.optString("browser_download_url", null);
                }
            }
            if (r.version.isEmpty()) throw new Exception("发布信息不完整");
            return r;
        } finally {
            c.disconnect();
        }
    }

    // ===================== dialogs =====================

    private static void showUpdateDialog(MainActivity a, Release r, String installed, boolean manual) {
        StringBuilder msg = new StringBuilder();
        msg.append("当前版本：").append(installed).append('\n');
        msg.append("最新版本：").append(r.version);
        if (r.apkSize > 0) msg.append(String.format(Locale.ROOT, "（%.1f MB）", r.apkSize / 1048576.0));
        if (r.sha256 != null || r.shaUrl != null) msg.append("\n下载后会校验 SHA-256");
        String notes = cleanNotes(r.notes);
        if (!notes.isEmpty()) msg.append("\n\n").append(notes);
        AlertDialog.Builder b = new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("发现新版本 " + r.tag)
                .setMessage(msg.toString())
                .setNegativeButton("以后再说", null);
        if (r.apkUrl != null) {
            b.setPositiveButton("立即更新", (d, w) -> download(a, r));
        } else {
            b.setPositiveButton("打开发布页", (d, w) -> a.openExternal(r.page));
        }
        if (!manual) b.setNeutralButton("跳过此版本", (d, w) -> Prefs.put(a, Prefs.SKIP_VERSION, r.tag));
        b.show();
    }

    /** Release notes are markdown; keep them readable as plain text. */
    static String cleanNotes(String md) {
        if (md == null) return "";
        String s = md.replace("\r", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("(?m)^\\s*[-*]\\s+", "• ")
                .replaceAll("\\[([^\\]]+)\\]\\((https?://[^)]+)\\)", "$1")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        if (s.length() > 2500) s = s.substring(0, 2500) + "…";
        return s;
    }

    // ===================== download =====================

    private static File downloadDir(Activity a) {
        // Android 7+: private cache, shared through FileProvider. Older: app-specific
        // external storage, which the system installer can read through a file:// URI.
        File base = Build.VERSION.SDK_INT >= 24 ? a.getCacheDir() : a.getExternalCacheDir();
        if (base == null) base = a.getCacheDir();
        File dir = new File(base, "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static void download(MainActivity a, Release r) {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        int pad = Math.round(20 * a.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        final ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(r.apkSize <= 0);
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        final TextView label = new TextView(a);
        label.setTextColor(0xFFCCCCCC);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setText("准备下载…");
        box.addView(label);
        final AlertDialog dlg = new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("正在下载 " + r.tag)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("取消", (d, w) -> cancelled.set(true))
                .show();
        final WeakReference<MainActivity> ref = new WeakReference<>(a);
        final String safe = r.apkName.replaceAll("[^A-Za-z0-9._-]", "_");
        final File dir = downloadDir(a);

        IO.execute(() -> {
            File out = new File(dir, safe);
            File part = new File(dir, safe + ".part");
            String error = null;
            try {
                // keep a matching partial download (resume, 84); drop everything else
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) if (!f.equals(part)) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                String expect = r.sha256;
                if (expect == null && r.shaUrl != null) expect = fetchSha(r.shaUrl);
                java.util.List<String> urls = new java.util.ArrayList<>();
                // mirror only when the result can be verified (87)
                if (expect != null && Prefs.on(a, Prefs.UPDATE_MIRROR)) urls.add(MIRROR + r.apkUrl);
                urls.add(r.apkUrl);
                if (r.altApkUrl != null) urls.add(r.altApkUrl);
                Exception last = null;
                boolean ok = false;
                for (int u = 0; u < urls.size() && !ok; u++) {
                    for (int attempt = 0; attempt < 3 && !ok; attempt++) {
                        if (cancelled.get()) throw new InterruptedException();
                        try {
                            fetchTo(urls.get(u), part, r.apkSize, cancelled, bar, label);
                            String bad = null;
                            if (r.apkSize > 0 && part.length() != r.apkSize) bad = "下载不完整";
                            else if (expect != null && !expect.equalsIgnoreCase(fileSha(part))) bad = "安装包校验失败（SHA-256 不一致）";
                            if (bad != null) {
                                //noinspection ResultOfMethodCallIgnored
                                part.delete();
                                last = new Exception(bad + "，已删除，请重试");
                                Diag.log("update", bad + " from " + (u == 0 && urls.size() > 1 ? "first source" : "source " + u));
                                break;                   // this source gave a bad file: next source
                            }
                            ok = true;
                        } catch (InterruptedException ie) {
                            throw ie;
                        } catch (java.io.FileNotFoundException nf) {
                            last = nf;
                            break;                       // 404 on this URL: try the next one
                        } catch (Exception e) {
                            last = e;
                            Diag.log("update", "download try " + (attempt + 1) + ": " + e.getMessage());
                            try { Thread.sleep(1500L * (attempt + 1)); } catch (InterruptedException ie) { throw ie; }
                        }
                    }
                    if (!ok && u < urls.size() - 1) { //noinspection ResultOfMethodCallIgnored
                        part.delete();                   // different source: start that one clean
                    }
                }
                if (!ok) throw last != null ? last : new Exception("下载失败");
                if (!part.renameTo(out)) throw new Exception("无法保存安装包");
            } catch (InterruptedException e) {
                error = "";
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (e instanceof java.io.FileNotFoundException) error = "下载地址不存在（HTTP 404）";
            }
            final String ferr = error;
            MAIN.post(() -> {
                try { dlg.dismiss(); } catch (Exception ignored) {}
                MainActivity act = ref.get();
                if (act == null || act.isFinishing()) return;
                if (ferr != null) {
                    if (!ferr.isEmpty()) {
                        new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                                .setTitle("更新失败")
                                .setMessage(ferr)
                                .setPositiveButton("重试", (d, w) -> download(act, r))
                                .setNeutralButton("浏览器下载", (d, w) -> act.openExternal(r.apkUrl))
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                    return;
                }
                install(act, out);
            });
        });
    }

    /** One download attempt into part (resuming it with a Range request when possible). */
    private static void fetchTo(String src, File part, long size, AtomicBoolean cancelled, ProgressBar bar, TextView label) throws Exception {
        HttpURLConnection c = null;
        try {
            URL url = new URL(src);
            long have = part.exists() ? part.length() : 0;
            if (size > 0 && have >= size) have = 0;
            int code = 0;
            // GitHub redirects to its asset CDN; follow by hand so https->https hops always work
            for (int hop = 0; hop < 6; hop++) {
                c = (HttpURLConnection) url.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "YiWei-updater");
                c.setRequestProperty("Accept", "application/octet-stream");
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    c = null;
                    if (loc == null) throw new Exception("下载地址跳转失败");
                    if (!loc.startsWith("https://") && !loc.startsWith("/")) throw new Exception("下载地址不安全");
                    url = new URL(url, loc);
                    continue;
                }
                break;
            }
            if (c == null) throw new Exception("下载地址跳转次数过多");
            if (code == 404) throw new java.io.FileNotFoundException(src);
            boolean append = code == 206 && have > 0;
            if (code != 200 && !append) throw new Exception("下载失败：HTTP " + code);
            if (!append) have = 0;
            long len = c.getContentLength();
            long total = len > 0 ? len + have : size;
            long done = have, lastUi = 0;
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(part, append)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled.get()) throw new InterruptedException();
                    os.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 150) {
                        lastUi = now;
                        final long d0 = done, t0 = total;
                        MAIN.post(() -> progress(bar, label, d0, t0));
                    }
                }
            }
            if (total > 0 && done < total) throw new java.io.IOException("连接中断");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String fetchSha(String url) {
        HttpURLConnection c = null;
        try {
            for (int hop = 0; hop < 6; hop++) {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", "YiWei-updater");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    c = null;
                    continue;
                }
                if (code != 200) return null;
                String t = readAll(c.getInputStream()).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
                return t.matches("[0-9a-f]{64}") ? t : null;
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String fileSha(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder b = new StringBuilder();
        for (byte x : md.digest()) b.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return b.toString();
    }

    // ===================== what's new after an upgrade (138) =====================

    public static void showWhatsNew(final MainActivity a, final String version) {
        String saved = Prefs.str(a, Prefs.NOTES_FOR, "").equals(version) ? Prefs.str(a, Prefs.NOTES, "") : "";
        if (!saved.isEmpty()) { whatsNewDialog(a, version, saved); return; }
        final WeakReference<MainActivity> ref = new WeakReference<>(a);
        IO.execute(() -> {
            String notes = "";
            try { notes = fetchApi(API_TAG + "v" + version).notes; } catch (Exception ignored) {}
            final String n = notes;
            MAIN.post(() -> {
                MainActivity act = ref.get();
                if (act != null && !act.isFinishing() && !n.isEmpty()) whatsNewDialog(act, version, n);
            });
        });
    }

    private static void whatsNewDialog(MainActivity a, String version, String notes) {
        String body = cleanNotes(notes);
        if (body.isEmpty()) return;
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("已更新到 " + version + " · 本次更新")
                .setMessage(body)
                .setPositiveButton("知道了", null)
                .show();
    }

    private static void progress(ProgressBar bar, TextView label, long done, long total) {
        if (total > 0) {
            bar.setIndeterminate(false);
            bar.setProgress((int) Math.min(1000, done * 1000 / total));
            label.setText(String.format(Locale.ROOT, "%.1f / %.1f MB", done / 1048576.0, total / 1048576.0));
        } else {
            label.setText(String.format(Locale.ROOT, "%.1f MB", done / 1048576.0));
        }
    }

    // ===================== install =====================

    private static void install(MainActivity a, File apk) {
        // sanity check: same package, newer build
        try {
            PackageManager pm = a.getPackageManager();
            PackageInfo arch = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (arch != null && !a.getPackageName().equals(arch.packageName)) {
                Toast.makeText(a, "安装包不是 YiWei，已取消", Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Prefs.put(a, Prefs.PENDING_APK, apk.getAbsolutePath());
            new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("需要允许安装应用")
                    .setMessage("请在下一页为 YiWei 打开「允许安装未知应用 / 允许来自此来源的应用」，返回后会自动继续安装。")
                    .setPositiveButton("去开启", (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            a.openAppDetails();
                        }
                    })
                    .setNegativeButton("取消", (d, w) -> Prefs.get(a).edit().remove(Prefs.PENDING_APK).apply())
                    .show();
            return;
        }
        launchInstaller(a, apk);
    }

    @SuppressWarnings("deprecation")
    private static void launchInstaller(Activity a, File apk) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            if (Build.VERSION.SDK_INT >= 24) {
                Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".updates", apk);
                i.setDataAndType(uri, APK_MIME);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                //noinspection ResultOfMethodCallIgnored
                apk.setReadable(true, false);
                i.setDataAndType(Uri.fromFile(apk), APK_MIME);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            a.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(a, "无法打开系统安装器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ===================== versions =====================

    public static String installedVersion(Activity a) {
        try {
            PackageInfo i = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return i.versionName == null ? "0" : i.versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /**
     * Semantic-ish comparison: numeric dotted core, then a pre-release suffix
     * ("1.1.0-dev.12") sorts before the plain release ("1.1.0"). Leading v ignored.
     */
    public static int compareVersions(String a, String b) {
        a = a == null ? "" : a.trim();
        b = b == null ? "" : b.trim();
        if (a.startsWith("v") || a.startsWith("V")) a = a.substring(1);
        if (b.startsWith("v") || b.startsWith("V")) b = b.substring(1);
        String[] pa = splitPre(a), pb = splitPre(b);
        String[] na = pa[0].split("\\."), nb = pb[0].split("\\.");
        for (int i = 0; i < Math.max(na.length, nb.length); i++) {
            long x = i < na.length ? num(na[i]) : 0, y = i < nb.length ? num(nb[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        boolean preA = !pa[1].isEmpty(), preB = !pb[1].isEmpty();
        if (preA != preB) return preA ? -1 : 1;
        return 0;
    }

    private static String[] splitPre(String v) {
        int i = v.indexOf('-');
        int j = v.indexOf('+');
        if (j >= 0 && (i < 0 || j < i)) i = j;
        return i < 0 ? new String[]{v, ""} : new String[]{v.substring(0, i), v.substring(i + 1)};
    }

    private static long num(String s) {
        StringBuilder d = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch >= '0' && ch <= '9') d.append(ch);
            else break;
        }
        try { return d.length() == 0 ? 0 : Long.parseLong(d.toString()); } catch (Exception e) { return 0; }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }
}
