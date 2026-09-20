package com.anya.shortener.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62CodecTest {

    @Test
    @DisplayName("encodes to the requested width with left padding")
    void padsToLength() {
        assertThat(Base62Codec.encode(0, 7)).isEqualTo("0000000");
        assertThat(Base62Codec.encode(1, 7)).hasSize(7).endsWith("1");
        assertThat(Base62Codec.encode(61, 7)).endsWith("z");
        assertThat(Base62Codec.encode(62, 7)).endsWith("10");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 61, 62, 3843, 999_999, 3_521_614_606_207L})
    @DisplayName("decode reverses encode")
    void roundTrips(long value) {
        assertThat(Base62Codec.decode(Base62Codec.encode(value, 7))).isEqualTo(value);
    }

    @Test
    @DisplayName("7 characters address the full 62^7 space")
    void capacityMatchesSpec() {
        assertThat(Base62Codec.capacity(7)).isEqualTo(3_521_614_606_208L);
    }

    @Test
    @DisplayName("distinct inputs never collide")
    void noCollisionsAcrossRange() {
        Set<String> seen = new HashSet<>();
        for (long i = 0; i < 50_000; i++) {
            assertThat(seen.add(Base62Codec.encode(i, 7)))
                    .as("duplicate slug generated for %d", i)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("rejects negative input rather than emitting a malformed slug")
    void rejectsNegative() {
        assertThatThrownBy(() -> Base62Codec.encode(-1, 7))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("validation rejects characters outside the alphabet")
    void validatesAlphabet() {
        assertThat(Base62Codec.isValid("abc123Z")).isTrue();
        assertThat(Base62Codec.isValid("abc-123")).isFalse();
        assertThat(Base62Codec.isValid("abc/123")).isFalse();
        assertThat(Base62Codec.isValid("")).isFalse();
        assertThat(Base62Codec.isValid(null)).isFalse();
    }
}
