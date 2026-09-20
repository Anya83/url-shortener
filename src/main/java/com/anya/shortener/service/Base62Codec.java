package com.anya.shortener.service;

/**
 * Base62 ({@code 0-9A-Za-z}) encoding for slugs.
 *
 * <p>Base62 is chosen over Base64 because the alphabet is URL-safe with no
 * escaping and no padding character — a Base64 slug would need {@code +}, {@code /}
 * and {@code =} handled specially in a path segment.
 *
 * <p>Seven characters give 62^7 ≈ 3.52 x 10^12 distinct slugs, which is ample
 * headroom: at a million new links a day it would take roughly 9,600 years to
 * exhaust the space.
 */
public final class Base62Codec {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = 62;

    private Base62Codec() {
    }

    /** Encodes a non-negative value, left-padded with {@code '0'} to {@code length}. */
    public static String encode(long value, int length) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative, got " + value);
        }
        StringBuilder sb = new StringBuilder(length);
        long remaining = value;
        do {
            sb.append(ALPHABET.charAt((int) (remaining % BASE)));
            remaining /= BASE;
        } while (remaining > 0);

        while (sb.length() < length) {
            sb.append('0');
        }
        return sb.reverse().toString();
    }

    public static long decode(String slug) {
        long result = 0;
        for (int i = 0; i < slug.length(); i++) {
            int digit = ALPHABET.indexOf(slug.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("not a base62 string: " + slug);
            }
            result = result * BASE + digit;
        }
        return result;
    }

    /** Total number of distinct slugs representable in {@code length} characters. */
    public static long capacity(int length) {
        long total = 1;
        for (int i = 0; i < length; i++) {
            total *= BASE;
        }
        return total;
    }

    public static boolean isValid(String slug) {
        if (slug == null || slug.isEmpty()) {
            return false;
        }
        for (int i = 0; i < slug.length(); i++) {
            if (ALPHABET.indexOf(slug.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
