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
}
