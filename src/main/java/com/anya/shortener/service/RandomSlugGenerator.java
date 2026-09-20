package com.anya.shortener.service;

import com.anya.shortener.config.AppProperties;

import java.security.SecureRandom;

/**
 * Unguessable slugs drawn from {@link SecureRandom}.
 *
 * <p>Unlike {@link CounterSlugGenerator} this does not guarantee uniqueness, so
 * the caller must treat a duplicate-key error as a retry signal. In practice that
 * is rare: with 62^7 slugs, the collision probability stays below 1% until
 * roughly 8.4 million links exist, and the unique index makes any collision a
 * caught error rather than a silent overwrite.
 *
 * <p>Use this when links must not be enumerable — the counter strategy's ordering
 * is recoverable by anyone who reads the multiplier in the source.
 */
public class RandomSlugGenerator implements SlugGenerator {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final SecureRandom random = new SecureRandom();
    private final int slugLength;

    public RandomSlugGenerator(AppProperties props) {
        this.slugLength = props.slug().length();
    }

    @Override
    public String next() {
        StringBuilder sb = new StringBuilder(slugLength);
        for (int i = 0; i < slugLength; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    @Override
    public String strategy() {
        return "random";
    }
}
