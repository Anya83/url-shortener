package com.anya.shortener.web.error;

/** The submitted URL is unparseable or uses a scheme we refuse to redirect to. */
public class InvalidUrlException extends RuntimeException {
    public InvalidUrlException(String message) {
        super(message);
    }
}
