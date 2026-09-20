package com.anya.shortener.service;

import com.anya.shortener.web.error.InvalidUrlException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlNormalizerTest {

    private UrlNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new UrlNormalizer();
    }

    @Test
    @DisplayName("lowercases scheme and host but preserves path case")
    void canonicalisesCase() {
        assertThat(normalizer.normalize("HTTP://Example.COM/MyPath"))
                .isEqualTo("http://example.com/MyPath");
    }

    @Test
    @DisplayName("adds a root path so bare hosts collapse to one form")
    void addsRootPath() {
        assertThat(normalizer.normalize("https://example.com"))
                .isEqualTo("https://example.com/");
    }

    @Test
    @DisplayName("strips default ports so they do not create duplicate links")
    void stripsDefaultPorts() {
        assertThat(normalizer.normalize("http://example.com:80/a")).isEqualTo("http://example.com/a");
        assertThat(normalizer.normalize("https://example.com:443/a")).isEqualTo("https://example.com/a");
        assertThat(normalizer.normalize("https://example.com:8443/a"))
                .isEqualTo("https://example.com:8443/a");
    }

    @Test
    @DisplayName("drops the fragment, which never reaches the server anyway")
    void dropsFragment() {
        assertThat(normalizer.normalize("https://example.com/page#section"))
                .isEqualTo("https://example.com/page");
    }

    @Test
    @DisplayName("keeps the query string, which does change the destination")
    void keepsQuery() {
        assertThat(normalizer.normalize("https://example.com/s?q=cats&page=2"))
                .isEqualTo("https://example.com/s?q=cats&page=2");
    }

    @Test
    @DisplayName("punycodes internationalised hosts")
    void punycodesHost() {
        assertThat(normalizer.normalize("https://bücher.example/x"))
                .startsWith("https://xn--bcher-kva.example");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "file:///etc/passwd",
            "ftp://example.com/x"
    })
    @DisplayName("refuses schemes that would be unsafe to redirect a browser to")
    void rejectsDangerousSchemes(String url) {
        assertThatThrownBy(() -> normalizer.normalize(url))
                .isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not a url", "/relative/path", "http://"})
    @DisplayName("rejects empty, relative and malformed input")
    void rejectsMalformed(String url) {
        assertThatThrownBy(() -> normalizer.normalize(url))
                .isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://apple.com@evil.example/",
            "https://user:pass@example.com/path",
            "https://bücher.example@evil.example/"
    })
    @DisplayName("rejects embedded credentials, the classic phishing construction")
    void rejectsEmbeddedCredentials(String url) {
        assertThatThrownBy(() -> normalizer.normalize(url))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    @DisplayName("keeps an explicit port on an internationalised host")
    void keepsPortOnInternationalisedHost() {
        assertThat(normalizer.normalize("https://bücher.example:8443/x"))
                .isEqualTo("https://xn--bcher-kva.example:8443/x");
    }

    @Test
    @DisplayName("rejects URLs past the length cap")
    void rejectsOverlongUrls() {
        String longUrl = "https://example.com/" + "x".repeat(2100);
        assertThatThrownBy(() -> normalizer.normalize(longUrl))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("2048");
    }

    @Test
    @DisplayName("equivalent URLs hash identically, which is what makes dedup work")
    void hashIsStableAcrossEquivalentForms() {
        String a = normalizer.normalize("HTTP://Example.com:80/path");
        String b = normalizer.normalize("http://example.com/path");
        assertThat(normalizer.hash(a, "owner")).isEqualTo(normalizer.hash(b, "owner"));
    }

    @Test
    @DisplayName("the same URL hashes differently per owner, keeping tenants separate")
    void hashIsScopedByOwner() {
        String url = normalizer.normalize("https://example.com/path");
        assertThat(normalizer.hash(url, "alice")).isNotEqualTo(normalizer.hash(url, "bob"));
    }

    @Test
    @DisplayName("owner is length-prefixed so boundaries cannot be forged")
    void ownerBoundaryCannotBeForged() {
        String url = normalizer.normalize("https://example.com/");
        // Without length prefixing, ("ab", "c" + url) and ("abc", url) could
        // produce the same digest input.
        assertThat(normalizer.hash(url, "ab")).isNotEqualTo(normalizer.hash(url, "abc"));
    }
}
