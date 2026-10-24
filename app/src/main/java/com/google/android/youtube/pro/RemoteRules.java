package com.google.android.youtube.pro;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Remote rules (73): extra CSS hide selectors, so a YouTube redesign can be patched without a
 * new APK. Only ever fetched from this app's own repository (raw.githubusercontent.com/yywgov/youtube),
 * and only accepted when
 *   - rules.json matches the SHA-256 published next to it (rules.json.sha256),
 *   - it is small, has schema 1, and every selector is a plain selector (no braces, no at-rules),
 *   - its version is not older than the one already cached.
 * Anything else keeps the last good copy, or the built-in rules when there is none.
 * The page script parses every selector again on its own and drops broad / invalid ones.
 */
public final class RemoteRules {
    static final String BASE = "https://raw.githubusercontent.com/yywgov/youtube/main/rules/";
    private static final String FILE = "rules.json";
    private static final int MAX_BYTES = 32 * 1024;
    private static final long INTERVAL = 24L * 3600_000L;
    private static final String LAST_CHECK = "rulesLastCheck";
    private static final String LAST_RESULT = "rulesLastResult";

    private static final java.util.concurrent.ExecutorService IO = Bg.NET;   // shared pool (262)
    /** Mainland fallback (287): the same mirror the updater offers; only ever trusted with a hash fetched elsewhere. */
    static final String MIRROR = "https://ghfast.top/";
    static final String API_SHA = "https://api.github.com/repos/yywgov/youtube/contents/rules/rules.json.sha256?ref=main";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile String cache = null;

    private RemoteRules() {}

    /** Verified rules for the page, "" when off or none. Called from the JS bridge thread. */
    public static String cached(Context c) {
        if (!Prefs.on(c, Prefs.REMOTE_RULES)) return "";
        String s = cache;
        if (s != null) return s;
        try {
            File f = new File(c.getFilesDir(), FILE);
            s = f.exists() && f.length() <= MAX_BYTES ? read(new FileInputStream(f)) : "";
            if (!s.isEmpty() && validate(s) == null) s = "";
        } catch (Exception e) {
            s = "";
        }
        cache = s;
        applyBlockPaths(s);
        return s;
    }

    /** Extra network block paths (278) from verified rules, "" clears them. */
    private static void applyBlockPaths(String body) {
        java.util.List<String> l = new java.util.ArrayList<>();
        try {
            if (body != null && !body.isEmpty()) {
                JSONArray a = new JSONObject(body).optJSONArray("blockPaths");
                if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.optString(i, ""));
            }
        } catch (Exception ignored) {}
        com.google.android.youtube.pro.webview.AdBlocker.setExtraPaths(l);
    }

    public static String status(Context c) {
        if (!Prefs.on(c, Prefs.REMOTE_RULES)) return "已关闭，只用内置规则";
        String s = cached(c);
        int ver = 0, n = 0;
        try {
            if (!s.isEmpty()) {
                JSONObject o = new JSONObject(s);
                ver = o.optInt("version", 0);
                n = o.optJSONArray("hide") == null ? 0 : o.getJSONArray("hide").length();
            }
        } catch (Exception ignored) {}
        String last = Prefs.str(c, LAST_RESULT, "");
        int bp = com.google.android.youtube.pro.webview.AdBlocker.extraCount();
        return (ver > 0 ? "规则版本 " + ver + "（" + n + " 条" + (bp > 0 ? "，拦截路径 " + bp + " 条" : "") + "）" : "使用内置规则")
                + (last.isEmpty() ? "" : " · " + last);
    }

    public static void autoCheck(Context c) {
        if (!Prefs.on(c, Prefs.REMOTE_RULES)) return;
        long now = System.currentTimeMillis(), last = Prefs.get(c).getLong(LAST_CHECK, 0);
        if (last > 0 && now >= last && now - last < INTERVAL) return;
        check(c, null);
    }

    public static void checkNow(Context c, Runnable done) {
        if (!Prefs.on(c, Prefs.REMOTE_RULES)) {
            Toast.makeText(c, "远程规则已关闭", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(c, "正在检查规则…", Toast.LENGTH_SHORT).show();
        check(c, done);
    }

    private static void check(final Context ctx, final Runnable done) {
        final Context c = ctx.getApplicationContext();
        Prefs.put(c, LAST_CHECK, System.currentTimeMillis());
        IO.execute(() -> {
            String result;
            try {
                // direct first; if raw.githubusercontent.com is unreachable (common on mainland networks, 287)
                // the file comes from the mirror and its SHA-256 from GitHub directly (raw or API), so the
                // mirror can never change the rules
                String body = null, sum = null, route = "直连";
                try { body = get(BASE + FILE); } catch (Exception e) { Diag.log("rules", "direct: " + e.getMessage()); }
                try { sum = parseSum(get(BASE + FILE + ".sha256")); } catch (Exception e) { /* API below */ }
                if (sum == null) {
                    try { sum = parseSum(apiSha()); route = body == null ? "镜像" : "直连+API"; } catch (Exception e) { Diag.log("rules", "api: " + e.getMessage()); }
                }
                if (sum == null) throw new Exception("拿不到校验值");
                if (body == null) { body = get(MIRROR + BASE + FILE); route = "镜像"; }
                if (!sum.equals(sha256(body))) throw new Exception("校验不通过（" + route + "）");
                String err = validate(body);
                if (err != null) throw new Exception(err);
                int ver = new JSONObject(body).optInt("version", 0);
                int have = 0;
                String old = cached(c);
                if (!old.isEmpty()) have = new JSONObject(old).optInt("version", 0);
                if (ver < have) throw new Exception("版本比本机旧，忽略");
                if (ver > have || old.isEmpty()) {
                    File tmp = new File(c.getFilesDir(), FILE + ".tmp");
                    try (FileOutputStream os = new FileOutputStream(tmp)) { os.write(body.getBytes("UTF-8")); }
                    if (!tmp.renameTo(new File(c.getFilesDir(), FILE))) throw new Exception("保存失败");
                    cache = body;
                    applyBlockPaths(body);
                    // straight into the page (288): selectors and texts apply now, no reload needed
                    final String fresh = body;
                    MAIN.post(() -> MainActivity.js("window.ytl&&ytl.remote&&ytl.remote(" + JSONObject.quote(fresh) + ")"));
                    result = "已更新到版本 " + ver + "（已生效）· " + route;
                    Diag.log("rules", "updated to v" + ver + " via " + route);
                } else {
                    result = "已是最新 · " + route;
                }
            } catch (Exception e) {
                result = "检查失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                Diag.log("rules", result);
            }
            final String r = result;
            Prefs.put(c, LAST_RESULT, new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new java.util.Date()) + " " + r);
            MAIN.post(() -> {
                if (done != null) {
                    Toast.makeText(c, r, Toast.LENGTH_SHORT).show();
                    done.run();
                }
            });
        });
    }

    static String parseSum(String s) {
        if (s == null) return null;
        String t = s.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        return t.matches("[0-9a-f]{64}") ? t : null;
    }

    /** rules.json.sha256 through the GitHub contents API (base64), used when raw is blocked (287). */
    private static String apiSha() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(API_SHA).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "YiWei-rules");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            JSONObject o = new JSONObject(read(conn.getInputStream()));
            String b64 = o.optString("content", "").replace("\n", "");
            return new String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), "UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    /** null when acceptable, else the reason. */
    static String validate(String body) {
        try {
            if (body == null || body.isEmpty() || body.length() > MAX_BYTES) return "大小不符";
            JSONObject o = new JSONObject(body);
            if (o.optInt("schema", 0) != 1) return "格式版本不支持";
            if (o.optInt("version", 0) <= 0) return "缺少版本号";
            JSONArray h = o.optJSONArray("hide");
            if (h == null) return "缺少 hide";
            if (h.length() > 200) return "规则太多";
            for (int i = 0; i < h.length(); i++) {
                Object x = h.get(i);
                if (!(x instanceof String)) return "规则不是文本";
                String s = (String) x;
                if (s.length() == 0 || s.length() > 200) return "规则长度不符";
                for (char ch : s.toCharArray()) if ("{}<>;@\\".indexOf(ch) >= 0 || ch < 0x20) return "规则含不允许的字符";
            }
            // optional network block paths (278): each one narrow and never touching playback / lists / login
            JSONArray bp = o.optJSONArray("blockPaths");
            if (bp != null) {
                if (bp.length() > 30) return "拦截路径太多";
                for (int i = 0; i < bp.length(); i++) {
                    Object x = bp.get(i);
                    if (!(x instanceof String) || !Logic.validBlockPath((String) x)) return "拦截路径不允许：" + x;
                }
            }
            // optional sponsored-card labels for the page's text fallback (285)
            JSONArray t = o.optJSONArray("adText");
            if (t != null) {
                if (t.length() > 20) return "广告文字太多";
                for (int i = 0; i < t.length(); i++) {
                    Object x = t.get(i);
                    if (!(x instanceof String) || ((String) x).trim().isEmpty() || ((String) x).length() > 20) return "广告文字不符";
                }
            }
            return null;
        } catch (Exception e) {
            return "不是有效的 JSON";
        }
    }

    static String sha256(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return b.toString();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(false);   // never leave raw.githubusercontent.com
            conn.setRequestProperty("User-Agent", "YiWei-rules");
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            return read(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_BYTES) throw new java.io.IOException("too large");
            }
            return bos.toString("UTF-8");
        }
    }
}
