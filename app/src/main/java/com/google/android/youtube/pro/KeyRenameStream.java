package com.google.android.youtube.pro;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Streaming version of the watch-page ad-key rename (260): the HTML goes to the WebView while it is
 * still downloading, with every {@code "key"}, {@code \"key\"} and {@code \x22key\x22} renamed to
 * {@code key_ytl} on the way. Patterns are ASCII, so a UTF-8 page can be handled byte by byte (an ASCII
 * byte never sits inside a multi-byte character); a match split across two reads is held back until the
 * next read completes it. No Android classes, so the JVM tests cover it.
 */
public final class KeyRenameStream extends InputStream {
    private final InputStream in;
    private final byte[][] pats;
    private final byte[][] reps;
    private final int keep;          // longest pattern - 1: bytes that may start an unfinished match
    private byte[] carry = new byte[0];
    private byte[] out = new byte[0];
    private int outPos = 0;
    private boolean eof = false;
    private int renamed = 0;

    public KeyRenameStream(InputStream in, String[] keys) {
        this.in = in;
        int n = keys.length * 3;
        pats = new byte[n][];
        reps = new byte[n][];
        int max = 1, j = 0;
        for (String k : keys) {
            String[][] forms = {{"\"", "\""}, {"\\\"", "\\\""}, {"\\x22", "\\x22"}};
            for (String[] f : forms) {
                pats[j] = (f[0] + k + f[1]).getBytes(StandardCharsets.US_ASCII);
                reps[j] = (f[0] + k + "_ytl" + f[1]).getBytes(StandardCharsets.US_ASCII);
                max = Math.max(max, pats[j].length);
                j++;
            }
        }
        keep = max - 1;
    }

    /** How many keys were renamed so far. */
    public int renamed() { return renamed; }

    private boolean fill() throws IOException {
        while (outPos >= out.length) {
            if (eof && carry.length == 0) return false;
            if (!eof) {
                byte[] buf = new byte[32768];
                int r = in.read(buf);
                if (r < 0) eof = true;
                else if (r > 0) {
                    byte[] c = new byte[carry.length + r];
                    System.arraycopy(carry, 0, c, 0, carry.length);
                    System.arraycopy(buf, 0, c, carry.length, r);
                    carry = c;
                }
            }
            int limit = eof ? carry.length : carry.length - keep;
            if (limit <= 0) continue;
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(limit + 64);
            int i = 0;
            while (i < limit) {
                byte b = carry[i];
                int hit = -1;
                if (b == '"' || b == '\\') {
                    for (int p = 0; p < pats.length && hit < 0; p++) if (matches(carry, i, pats[p])) hit = p;
                }
                if (hit >= 0) {
                    o.write(reps[hit], 0, reps[hit].length);
                    i += pats[hit].length;
                    renamed++;
                } else {
                    o.write(b);
                    i++;
                }
            }
            byte[] rest = new byte[carry.length - i];
            System.arraycopy(carry, i, rest, 0, rest.length);
            carry = rest;
            out = o.toByteArray();
            outPos = 0;
        }
        return true;
    }

    private static boolean matches(byte[] buf, int at, byte[] pat) {
        if (at + pat.length > buf.length) return false;
        for (int k = 0; k < pat.length; k++) if (buf[at + k] != pat[k]) return false;
        return true;
    }

    @Override public int read() throws IOException {
        if (!fill()) return -1;
        return out[outPos++] & 0xFF;
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (!fill()) return -1;
        int n = Math.min(len, out.length - outPos);
        System.arraycopy(out, outPos, b, off, n);
        outPos += n;
        return n;
    }

    @Override public int available() { return out.length - outPos; }

    @Override public void close() throws IOException { in.close(); }
}
