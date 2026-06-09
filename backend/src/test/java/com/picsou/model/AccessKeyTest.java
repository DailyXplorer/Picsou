package com.picsou.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AccessKeyTest {

    private final Instant now = Instant.parse("2026-06-04T12:00:00Z");

    @Test
    void usable_whenNotRevokedAndNeverExpires() {
        AccessKey key = AccessKey.builder().build();
        assertThat(key.isUsable(now)).isTrue();
    }

    @Test
    void usable_whenExpiryInFuture() {
        AccessKey key = AccessKey.builder().expiresAt(now.plus(1, ChronoUnit.DAYS)).build();
        assertThat(key.isUsable(now)).isTrue();
    }

    @Test
    void notUsable_whenExpired() {
        AccessKey key = AccessKey.builder().expiresAt(now.minus(1, ChronoUnit.SECONDS)).build();
        assertThat(key.isUsable(now)).isFalse();
    }

    @Test
    void notUsable_whenRevoked() {
        AccessKey key = AccessKey.builder().revokedAt(now.minus(1, ChronoUnit.DAYS)).build();
        assertThat(key.isUsable(now)).isFalse();
    }

    @Test
    void notUsable_whenRevokedEvenIfExpiryInFuture() {
        AccessKey key = AccessKey.builder()
            .revokedAt(now)
            .expiresAt(now.plus(10, ChronoUnit.DAYS))
            .build();
        assertThat(key.isUsable(now)).isFalse();
    }

    @Test
    void toString_neverLeaksTheHash() {
        AccessKey key = AccessKey.builder().keyPrefix("psk_abcd1234").keyHash("DEADBEEF").build();
        assertThat(key.toString()).doesNotContain("DEADBEEF");
    }
}
