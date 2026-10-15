package com.fluxo.ticket;

/**
 * Lexicographic ranks over base 36 ('0'-'9', 'a'-'z'): a rank can always be generated between two
 * others without renumbering (spec §4.10). Generated ranks never end with '0', which keeps
 * "strictly between" always possible.
 */
public final class Rank {

    private static final int BASE = 36;
    private static final int WIDTH = 6; // 36^6 ≈ 2 billion steps before an open-ended insert must grow

    private Rank() {
    }

    /** A rank strictly between {@code before} and {@code after}; either may be null (open bound). */
    public static String between(String before, String after) {
        String a = before == null ? "" : before;
        String b = after;
        if (b != null && a.compareTo(b) >= 0) {
            throw new IllegalArgumentException("before must be < after: " + before + " / " + after);
        }
        // Appending/prepending is the common case: step by one instead of halving, so ranks stay short.
        if (before != null && b == null) {
            String r = step(a, +1);
            if (r != null) return r;
        }
        if (before == null && b != null) {
            String r = step(b, -1);
            if (r != null && r.compareTo(b) < 0) return r;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; ; i++) {
            int da = i < a.length() ? digit(a.charAt(i)) : 0;
            int db = b == null ? BASE : i < b.length() ? digit(b.charAt(i)) : 0;
            if (da == db) {
                sb.append(ch(da));
                continue;
            }
            int mid = (da + db) / 2;
            if (mid > da) {
                return sb.append(ch(mid)).toString();
            }
            // Adjacent digits: keep a's digit and look for room after it, with no upper bound.
            sb.append(ch(da));
            b = null;
        }
    }

    /** Adds delta (±1) to the rank padded to WIDTH digits; null on overflow/underflow. Never ends with '0'. */
    private static String step(String rank, int delta) {
        char[] d = (rank.length() >= WIDTH ? rank : rank + "0".repeat(WIDTH - rank.length())).toCharArray();
        do {
            int i = d.length - 1;
            int carry = delta;
            while (carry != 0) {
                if (i < 0) return null;
                int v = digit(d[i]) + carry;
                carry = v < 0 ? -1 : v >= BASE ? 1 : 0;
                d[i] = ch(Math.floorMod(v, BASE));
                i--;
            }
        } while (d[d.length - 1] == '0');
        String r = new String(d);
        return r.chars().allMatch(c -> c == '0') ? null : r;
    }

    private static int digit(char c) {
        return Character.digit(c, BASE);
    }

    private static char ch(int d) {
        return Character.forDigit(d, BASE);
    }
}
