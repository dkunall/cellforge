package com.cellforge.cell.work.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Fractional indexing for Kanban order: a rank is a base-62 fraction written as a string, so moving a card
 * writes one row (a key between its new neighbours) instead of renumbering the column. Digits are in ASCII
 * order, which is how the column compares them (COLLATE "C"). Keys never end in '0', which guarantees there
 * is always room between two of them.
 */
public final class Rank {

    static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = DIGITS.length();

    /** Past this length a column is respread; repeated inserts at one spot grow keys by about a digit each 6 moves. */
    public static final int REBALANCE_LENGTH = 24;

    private Rank() {
    }

    /**
     * A key strictly between {@code before} and {@code after}. Null means open-ended: {@code between(null, x)}
     * sorts first, {@code between(x, null)} sorts last.
     */
    public static String between(String before, String after) {
        String a = before == null ? "" : before;
        if (after != null && a.compareTo(after) >= 0) {
            throw new IllegalArgumentException("'" + before + "' is not before '" + after + "'");
        }
        String key = midpoint(a, after);
        if (!key.isEmpty() && key.charAt(key.length() - 1) == '0') {
            throw new IllegalStateException("rank with trailing zero: " + key);
        }
        return key;
    }

    private static String midpoint(String a, String b) {
        if (b != null) {
            int n = 0;
            while (n < b.length() && (n < a.length() ? a.charAt(n) : '0') == b.charAt(n)) {
                n++;
            }
            if (n > 0) {
                return b.substring(0, n) + midpoint(a.length() > n ? a.substring(n) : "", b.substring(n));
            }
        }
        int low = a.isEmpty() ? 0 : DIGITS.indexOf(a.charAt(0));
        int high = b != null ? DIGITS.indexOf(b.charAt(0)) : BASE;
        if (high - low > 1) {
            return String.valueOf(DIGITS.charAt((low + high + 1) / 2));
        }
        if (b != null && b.length() > 1) {
            return b.substring(0, 1);
        }
        return DIGITS.charAt(low) + midpoint(a.length() > 1 ? a.substring(1) : "", null);
    }

    /**
     * {@code count} keys evenly spread over the whole range, all the same short length, for rebalancing a column.
     */
    public static List<String> spread(int count) {
        int length = 1;
        while (Math.pow(BASE, length) <= count * 2.0) {
            length++;
        }
        double step = Math.pow(BASE, length) / (count + 1);
        List<String> keys = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            keys.add(digits((long) Math.floor(step * i), length));
        }
        return keys;
    }

    private static String digits(long value, int length) {
        char[] out = new char[length];
        for (int i = length - 1; i >= 0; i--) {
            out[i] = DIGITS.charAt((int) (value % BASE));
            value /= BASE;
        }
        int end = length;
        while (end > 1 && out[end - 1] == '0') {
            end--;
        }
        return new String(out, 0, end);
    }
}
