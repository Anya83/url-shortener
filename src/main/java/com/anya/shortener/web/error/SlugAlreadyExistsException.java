package com.anya.shortener.web.error;

/** A custom alias was requested but is already taken or reserved. */
public class SlugAlreadyExistsException extends RuntimeException {
    public SlugAlreadyExistsException(String slug) {
        super("Slug '" + slug + "' is already in use");
    }
}
