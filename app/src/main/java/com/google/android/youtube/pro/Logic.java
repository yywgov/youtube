package com.google.android.youtube.pro;

/** Small pure functions shared by several classes; no Android classes so JVM tests can cover them (417). */
public final class Logic {
    private Logic() {}

    /**
     * Fade-out volume for the sleep timer (298): (left / fade)², so the drop is audible from the
     * start of the fade and the last seconds don't fall off a cliff. 1 before the fade, 0 at the end.
     */
    public static double fadeVolume(double left, double fade) {
        if (!(fade > 0)) return left > 0 ? 1 : 0;
        double x = left / fade;
        if (x >= 1) return 1;
        if (x <= 0) return 0;
        return x * x;
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

    /**
     * Release notes are markdown; keep them readable as plain text. GitHub's generated English
     * "What's Changed / Full Changelog" tail is cut off (397).
     */
    public static String cleanNotes(String md) {
        if (md == null) return "";
        String s = md.replace("\r", "");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\s*(?:#{1,6}\\s*What's Changed|\\*\\*Full Changelog\\*\\*|Full Changelog:|#{1,6}\\s*New Contributors)").matcher(s);
        if (m.find()) s = s.substring(0, m.start());
        s = s.replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("(?m)^\\s*[-*]\\s+", "• ")
                .replaceAll("\\[([^\\]]+)\\]\\((https?://[^)]+)\\)", "$1")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        if (s.length() > 2500) s = s.substring(0, 2500) + "…";
        return s;
    }

    /** Only YouTube pages get pull-to-refresh (349): never the Google sign-in pages. */
    public static boolean isYouTubeHost(String url) {
        if (url == null) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^https?://([^/?#:]+)").matcher(url);
        if (!m.find()) return false;
        String h = m.group(1).toLowerCase(java.util.Locale.ROOT);
        return h.equals("youtube.com") || h.endsWith(".youtube.com");
    }

    /** Night window (307): hours [start, end) on a 24 h clock, wrapping past midnight (22–5). */
    public static boolean inNight(int hour, int start, int end) {
        if (start == end) return false;
        return start < end ? hour >= start && hour < end : hour >= start || hour < end;
    }

    /** The night a time belongs to (307): before the window's end it is still the previous evening's. */
    public static boolean nightBelongsToYesterday(int hour, int start, int end) {
        return start > end && hour < end;
    }

    /**
     * Inactivity guard (277): playing, no sleep timer, the guard on, and no touch / key / media
     * button for {@code limitMs}.
     */
    public static boolean idleDue(boolean enabled, boolean playing, boolean timerActive, long lastInputMs, long nowMs, long limitMs) {
        return enabled && playing && !timerActive && lastInputMs > 0 && nowMs - lastInputMs >= limitMs;
    }

    /** SponsorBlock privacy lookup (410): first 4 hex digits of sha256(videoId). */
    public static String sbPrefix(String videoId) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(videoId.getBytes("UTF-8"));
            return String.format(java.util.Locale.ROOT, "%02x%02x", d[0] & 0xff, d[1] & 0xff);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Bridge methods that act on the app (409) are refused while the main frame is a known
     * non-YouTube page (Google sign-in and friends). Unknown / empty means "not known", which is
     * allowed, so a YouTube page is never refused because a navigation was not reported yet.
     */
    public static boolean bridgeAllowed(String host) {
        if (host == null || host.isEmpty()) return true;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("youtube.com") || h.endsWith(".youtube.com");
    }

    /** Host of a URL, lower case, "" when there is none. */
    public static String hostOf(String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#:]+)").matcher(url);
        return m.find() ? m.group(1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    /** Microphone for voice search (411): only YouTube pages, by host, not by substring. */
    public static boolean micOriginOk(String origin) {
        String h = hostOf(origin);
        return h.equals("youtube.com") || h.endsWith(".youtube.com");
    }

    /** Release-note lines that were markdown headings (399), so they can be shown in bold. */
    public static java.util.List<String> noteHeadings(String md) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (md == null) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^#{1,6}\\s*(.+?)\\s*$").matcher(md.replace("\r", ""));
        while (m.find()) {
            String h = m.group(1).replaceAll("\\*\\*(.+?)\\*\\*", "$1").trim();
            if (!h.isEmpty() && !h.startsWith("What's Changed") && !h.startsWith("New Contributors")) out.add(h);
        }
        return out;
    }

    /** Sleep badge for a minutes timer (308): minutes, and in the last minute seconds in steps of 5. */
    public static String sleepBadge(long leftMs) {
        if (leftMs < 60_000L) {
            long s = Math.max(5L, ((Math.max(0L, leftMs) + 4_999L) / 5_000L) * 5L);
            return "睡眠 · " + Math.min(60L, s) + " 秒";
        }
        return "睡眠 · " + ((leftMs + 59_999L) / 60_000L) + " 分";
    }

    /** One night of the sleep log (276), e.g. "播放 3 小时 10 分，耗电 9%". */
    public static String nightSummary(long minutes, int drop) {
        String t = minutes >= 60 ? (minutes / 60) + " 小时 " + (minutes % 60) + " 分" : minutes + " 分钟";
        return "播放 " + t + "，耗电 " + Math.max(0, drop) + "%";
    }

    /** Refresh-rate samples while scrolling (391): mostly below the panel's best = held back. */
    public static boolean refreshLimited(int samples, int low, float maxHz) {
        return maxHz > 90f && samples >= 10 && low * 10 >= samples * 8;
    }
}
