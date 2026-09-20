package com.anya.shortener.service;

/** Produces the short code that identifies a link. */
public interface SlugGenerator {

    /**
     * Returns the next slug. Implementations may or may not guarantee uniqueness;
     * callers must still be prepared for a duplicate-key error from Mongo and
     * retry. See {@link RandomSlugGenerator}.
     */
    String next();

    /** Identifier used for logging and the {@code app.slug.strategy} switch. */
    String strategy();
}
