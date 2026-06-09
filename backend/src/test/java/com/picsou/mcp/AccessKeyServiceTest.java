package com.picsou.mcp;

import com.picsou.model.AccessKey;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AccessKeyRepository;
import com.picsou.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessKeyServiceTest {

    @Mock AccessKeyRepository accessKeyRepository;
    @Mock AppUserRepository userRepository;
    @Mock AccessKeyUsageRecorder usageRecorder;
    @Captor ArgumentCaptor<AccessKey> keyCaptor;

    static final Instant T0 = Instant.parse("2026-06-04T12:00:00Z");
    static final long THROTTLE_SECONDS = 300;

    TickingClock clock;
    AccessKeyService service;
    FamilyMember member;
    AppUser owner;

    @BeforeEach
    void setUp() {
        clock = new TickingClock(T0);
        service = new AccessKeyService(accessKeyRepository, userRepository, usageRecorder, clock, THROTTLE_SECONDS);
        member = FamilyMember.builder().id(42L).build();
        owner = AppUser.builder().id(7L).username("alice").member(member).activated(true).build();
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    void create_returnsSecretWithPskPrefixAndStoresOnlyTheHash() {
        AccessKeyService.GeneratedKey gen =
            service.create(owner, "Claude Desktop", Set.of(Scopes.ACCOUNTS_READ), null);

        assertThat(gen.rawSecret()).matches("^psk_[0-9A-Za-z]{32}$");

        verify(accessKeyRepository).save(keyCaptor.capture());
        AccessKey saved = keyCaptor.getValue();
        assertThat(saved.getKeyPrefix()).isEqualTo(gen.rawSecret().substring(0, 12));
        assertThat(saved.getKeyHash())
            .isEqualTo(AccessKeyService.sha256Hex(gen.rawSecret()))
            .isNotEqualTo(gen.rawSecret());
        assertThat(saved.getMember()).isSameAs(member);
        assertThat(saved.getCreatedBy()).isEqualTo(7L);
        assertThat(saved.getName()).isEqualTo("Claude Desktop");
        assertThat(saved.getScopes()).containsExactly(Scopes.ACCOUNTS_READ);
        assertThat(saved.getExpiresAt()).isNull();
    }

    @Test
    void create_storesGivenExpiry() {
        Instant expiry = T0.plusSeconds(86_400);
        service.create(owner, "k", Set.of(Scopes.GOALS_READ), expiry);
        verify(accessKeyRepository).save(keyCaptor.capture());
        assertThat(keyCaptor.getValue().getExpiresAt()).isEqualTo(expiry);
    }

    @Test
    void create_generatesUniqueSecretsAcrossCalls() {
        String a = service.create(owner, "a", Set.of(Scopes.ACCOUNTS_READ), null).rawSecret();
        String b = service.create(owner, "b", Set.of(Scopes.ACCOUNTS_READ), null).rawSecret();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void create_rejectsUnknownScope_andSavesNothing() {
        assertThatThrownBy(() ->
            service.create(owner, "k", Set.of(Scopes.ACCOUNTS_READ, "evil:scope"), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("evil:scope");
        verify(accessKeyRepository, never()).save(any());
    }

    // ── validate: happy path ───────────────────────────────────────────────────

    @Test
    void validate_resolvesOwnerAndScopes_forValidKey() {
        String secret = "psk_abcd1234efgh5678ijkl9012mnop3456";
        AccessKey stored = storedKeyFor(secret, Set.of(Scopes.ACCOUNTS_READ, Scopes.GOALS_READ));
        when(accessKeyRepository.findByKeyPrefix(secret.substring(0, 12))).thenReturn(Optional.of(stored));
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(owner));

        Optional<AccessKeyService.ResolvedKey> result = service.validate(secret);

        assertThat(result).isPresent();
        assertThat(result.get().owner()).isSameAs(owner);
        assertThat(result.get().scopes()).containsExactlyInAnyOrder(Scopes.ACCOUNTS_READ, Scopes.GOALS_READ);
        assertThat(result.get().keyId()).isEqualTo(5L);
    }

    // ── validate: rejection paths ───────────────────────────────────────────────

    @Test
    void validate_emptyForMalformedKey() {
        assertThat(service.validate(null)).isEmpty();
        assertThat(service.validate("")).isEmpty();
        assertThat(service.validate("not-a-psk-key")).isEmpty();
        assertThat(service.validate("psk_short")).isEmpty();
        verifyNoInteractions(accessKeyRepository, userRepository);
    }

    @Test
    void validate_emptyForUnknownPrefix() {
        String secret = "psk_abcd1234efgh5678ijkl9012mnop3456";
        when(accessKeyRepository.findByKeyPrefix(any())).thenReturn(Optional.empty());
        assertThat(service.validate(secret)).isEmpty();
        verifyNoInteractions(userRepository);
    }

    @Test
    void validate_emptyForHashMismatch() {
        String realSecret = "psk_abcd1234efgh5678ijkl9012mnop3456";
        String forged     = "psk_abcd1234ZZZZZZZZZZZZZZZZZZZZZZZZ"; // same prefix, wrong body
        AccessKey stored = storedKeyFor(realSecret, Set.of(Scopes.ACCOUNTS_READ));
        when(accessKeyRepository.findByKeyPrefix(forged.substring(0, 12))).thenReturn(Optional.of(stored));
        assertThat(service.validate(forged)).isEmpty();
        verifyNoInteractions(userRepository);
    }

    @Test
    void validate_emptyForRevokedKey() {
        String secret = "psk_abcd1234efgh5678ijkl9012mnop3456";
        AccessKey stored = storedKeyFor(secret, Set.of(Scopes.ACCOUNTS_READ));
        stored.setRevokedAt(T0.minusSeconds(10));
        when(accessKeyRepository.findByKeyPrefix(secret.substring(0, 12))).thenReturn(Optional.of(stored));
        assertThat(service.validate(secret)).isEmpty();
        verifyNoInteractions(userRepository);
    }

    @Test
    void validate_emptyForExpiredKey() {
        String secret = "psk_abcd1234efgh5678ijkl9012mnop3456";
        AccessKey stored = storedKeyFor(secret, Set.of(Scopes.ACCOUNTS_READ));
        stored.setExpiresAt(T0.minusSeconds(1));
        when(accessKeyRepository.findByKeyPrefix(secret.substring(0, 12))).thenReturn(Optional.of(stored));
        assertThat(service.validate(secret)).isEmpty();
    }

    @Test
    void validate_emptyWhenOwnerMissing() {
        String secret = "psk_abcd1234efgh5678ijkl9012mnop3456";
        AccessKey stored = storedKeyFor(secret, Set.of(Scopes.ACCOUNTS_READ));
        when(accessKeyRepository.findByKeyPrefix(secret.substring(0, 12))).thenReturn(Optional.of(stored));
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.empty());
        assertThat(service.validate(secret)).isEmpty();
    }

    @Test
    void validate_emptyWhenOwnerNotActivated() {
        String secret = "psk_abcd1234efgh5678ijkl9012mnop3456";
        AccessKey stored = storedKeyFor(secret, Set.of(Scopes.ACCOUNTS_READ));
        owner.setActivated(false);
        when(accessKeyRepository.findByKeyPrefix(secret.substring(0, 12))).thenReturn(Optional.of(stored));
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(owner));
        assertThat(service.validate(secret)).isEmpty();
    }

    // ── validate: last_used_at throttling ───────────────────────────────────────

    @Test
    void validate_recordsUsage_onFirstUse() {
        String secret = validStubbedKey();
        service.validate(secret);
        verify(usageRecorder).touch(5L, T0);
    }

    @Test
    void validate_skipsUsage_withinThrottleWindow() {
        String secret = validStubbedKey();
        service.validate(secret);
        service.validate(secret);   // same instant
        verify(usageRecorder, times(1)).touch(eq(5L), any());
    }

    @Test
    void validate_recordsUsageAgain_afterThrottleWindow() {
        String secret = validStubbedKey();
        service.validate(secret);
        clock.advanceSeconds(THROTTLE_SECONDS + 1);
        service.validate(secret);
        verify(usageRecorder, times(2)).touch(eq(5L), any());
    }

    // ── list ────────────────────────────────────────────────────────────────────

    @Test
    void list_returnsMemberKeysNewestFirst() {
        AccessKey k1 = AccessKey.builder().id(1L).build();
        AccessKey k2 = AccessKey.builder().id(2L).build();
        when(accessKeyRepository.findByMemberIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(k1, k2));
        assertThat(service.list(42L)).containsExactly(k1, k2);
    }

    // ── revoke ────────────────────────────────────────────────────────────────────

    @Test
    void revoke_setsRevokedAt_forOwnKey() {
        AccessKey key = AccessKey.builder().id(3L).build();
        when(accessKeyRepository.findByIdAndMemberId(3L, 42L)).thenReturn(Optional.of(key));
        assertThat(service.revoke(3L, 42L)).isTrue();
        assertThat(key.getRevokedAt()).isEqualTo(T0);
        verify(accessKeyRepository).save(key);
    }

    @Test
    void revoke_returnsFalse_whenKeyNotFoundForMember() {
        when(accessKeyRepository.findByIdAndMemberId(99L, 42L)).thenReturn(Optional.empty());
        assertThat(service.revoke(99L, 42L)).isFalse();
        verify(accessKeyRepository, never()).save(any());
    }

    @Test
    void revoke_isIdempotent_whenAlreadyRevoked() {
        AccessKey key = AccessKey.builder().id(3L).revokedAt(T0.minusSeconds(999)).build();
        when(accessKeyRepository.findByIdAndMemberId(3L, 42L)).thenReturn(Optional.of(key));
        assertThat(service.revoke(3L, 42L)).isTrue();
        assertThat(key.getRevokedAt()).isEqualTo(T0.minusSeconds(999)); // unchanged
        verify(accessKeyRepository, never()).save(any());
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /** Builds a stored AccessKey whose hash matches {@code secret}, owned by user 7 / member 42. */
    private AccessKey storedKeyFor(String secret, Set<String> scopes) {
        return AccessKey.builder()
            .id(5L)
            .createdBy(7L)
            .member(member)
            .keyPrefix(secret.substring(0, 12))
            .keyHash(AccessKeyService.sha256Hex(secret))
            .scopes(scopes)
            .build();
    }

    /** Stubs a fully valid key + activated owner and returns its raw secret. */
    private String validStubbedKey() {
        String secret = "psk_abcd1234efgh5678ijkl9012mnop3456";
        AccessKey stored = storedKeyFor(secret, Set.of(Scopes.ACCOUNTS_READ));
        when(accessKeyRepository.findByKeyPrefix(secret.substring(0, 12))).thenReturn(Optional.of(stored));
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(owner));
        return secret;
    }

    /** Real, advanceable clock — avoids mocking java.time and keeps strict stubbing happy. */
    static final class TickingClock extends Clock {
        private Instant now;
        TickingClock(Instant start) { this.now = start; }
        void advanceSeconds(long s) { now = now.plusSeconds(s); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
