package com.anya.shortener.web.error;

/** No resolvable link exists for the requested slug. */
public class SlugNotFoundException extends RuntimeException {

    private final String slug;

    public SlugNotFoundException(String slug) {
        super("No link found for slug '" + slug + "'");
        this.slug = slug;
    }

    public String getSlug() {
        return slug;
    }
}
