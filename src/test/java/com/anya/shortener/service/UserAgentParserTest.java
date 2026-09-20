package com.anya.shortener.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserAgentParserTest {

    private UserAgentParser parser;

    @BeforeEach
    void setUp() {
        parser = new UserAgentParser();
    }

    @Test
    @DisplayName("Edge is not misreported as Chrome despite claiming to be Chrome")
    void edgeBeatsChrome() {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0";
        UserAgentParser.Parsed parsed = parser.parse(ua);
        assertThat(parsed.browser()).isEqualTo("Edge");
        assertThat(parsed.os()).isEqualTo("Windows");
        assertThat(parsed.deviceType()).isEqualTo("desktop");
    }

    @Test
    @DisplayName("Chrome is not misreported as Safari despite claiming to be Safari")
    void chromeBeatsSafari() {
        String ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        UserAgentParser.Parsed parsed = parser.parse(ua);
        assertThat(parsed.browser()).isEqualTo("Chrome");
        assertThat(parsed.os()).isEqualTo("macOS");
    }

    @Test
    @DisplayName("iOS wins over macOS even though iOS UAs say 'like Mac OS X'")
    void iosBeatsMacOs() {
        String ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_1 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Mobile/15E148 Safari/604.1";
        UserAgentParser.Parsed parsed = parser.parse(ua);
        assertThat(parsed.os()).isEqualTo("iOS");
        assertThat(parsed.deviceType()).isEqualTo("mobile");
        assertThat(parsed.browser()).isEqualTo("Safari");
    }

    @Test
    @DisplayName("an iPad is a tablet, not a mobile")
    void ipadIsTablet() {
        String ua = "Mozilla/5.0 (iPad; CPU OS 17_1 like Mac OS X) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Version/17.1 Safari/604.1";
        assertThat(parser.parse(ua).deviceType()).isEqualTo("tablet");
    }

    @Test
    @DisplayName("Android without 'mobile' is a tablet")
    void androidTabletDetected() {
        String ua = "Mozilla/5.0 (Linux; Android 13; SM-X710) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        UserAgentParser.Parsed parsed = parser.parse(ua);
        assertThat(parsed.deviceType()).isEqualTo("tablet");
        assertThat(parsed.os()).isEqualTo("Android");
    }

    @Test
    @DisplayName("Android with 'mobile' is a phone")
    void androidPhoneDetected() {
        String ua = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
        assertThat(parser.parse(ua).deviceType()).isEqualTo("mobile");
    }

    @Test
    @DisplayName("bots are bucketed separately so they do not inflate human traffic")
    void detectsBots() {
        assertThat(parser.parse("Googlebot/2.1 (+http://www.google.com/bot.html)").deviceType())
                .isEqualTo("bot");
        assertThat(parser.parse("curl/8.4.0").deviceType()).isEqualTo("bot");
    }

    @Test
    @DisplayName("missing or empty user agents do not blow up")
    void handlesMissingUserAgent() {
        assertThat(parser.parse(null).deviceType()).isEqualTo("unknown");
        assertThat(parser.parse("").browser()).isEqualTo("unknown");
    }
}
