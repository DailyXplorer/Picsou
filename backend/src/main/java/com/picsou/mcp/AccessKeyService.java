package com.picsou.mcp;

import com.picsou.model.AccessKey;
import com.picsou.model.AppUser;
import com.picsou.repository.AccessKeyRepository;
import com.picsou.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues, validates, lists, and revokes scoped access-keys for the embedded MCP server.
 *
 * <p>Key format: {@code psk_} + 32 base62 chars (~190 bits of entropy). Because the secret is
 * high-entropy, a fast hash (SHA-256) is safe — we look it up by its unique {@code key_prefix}
 * then constant-time compare the full hash. The raw secret is returned to the caller exactly once
 * (at creation) and never stored. {@link #validate} runs per request with no caching, so revoke
 * and expiry take effect immediately.
 */
@Service
public class AccessKeyService {

    private static final Logger log = LoggerFactory.getLogger(AccessKeyService.class);

    private static final String PREFIX = "psk_";
    private static final int BODY_LENGTH = 32;
    private static final int FULL_LENGTH = PREFIX.length() + BODY_LENGTH;          // 36
    private static final int PREFIX_LENGTH = PREFIX.length() + 8;                  // psk_ + 8 chars
    private static final char[] BASE62 =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final AccessKeyRepository accessKeyRepository;
    private final AppUserRepository userRepository;
    private final AccessKeyUsageRecorder usageRecorder;
    private final Clock clock;
    private final long touchThrottleSeconds;

    private final SecureRandom random = new SecureRandom();
    /** keyId → last instant we persisted last_used_at, to throttle hot-path writes. */
    private final ConcurrentHashMap<Long, Instant> lastTouch = new ConcurrentHashMap<>();

    public AccessKeyService(
        AccessKeyRepository accessKeyRepository,
        AppUserRepository userRepository,
        AccessKeyUsageRecorder usageRecorder,
        Clock clock,
        @Value("${app.access-key.touch-throttle-seconds:300}") long touchThrottleSeconds
    ) {
        this.accessKeyRepository = accessKeyRepository;
        this.userRepository = userRepository;
        this.usageRecorder = usageRecorder;
        this.clock = clock;
        this.touchThrottleSeconds = touchThrottleSeconds;
    }

    /**
     * Create a key for {@code owner}, bound to the owner's member. Validates the requested scopes
     * against {@link Scopes#ALL}. Returns the entity plus the raw secret — the ONLY time the secret
     * is available in plaintext.
     */
    @Transactional
    public GeneratedKey create(AppUser owner, String name, Set<String> scopes, Instant expiresAt) {
        validateScopes(scopes);
        String raw = PREFIX + generateBody();
        AccessKey key = AccessKey.builder()
            .member(owner.getMember())
            .createdBy(owner.getId())
            .name(name)
            .keyPrefix(raw.substring(0, PREFIX_LENGTH))
            .keyHash(sha256Hex(raw))
            .scopes(new LinkedHashSet<>(scopes))
            .expiresAt(expiresAt)
            .build();
        accessKeyRepository.save(key);
        return new GeneratedKey(key, raw);
    }

    /**
     * Resolve a presented raw key to its owning {@link AppUser} and granted scopes, or empty if the
     * key is malformed, unknown, forged, revoked, expired, or its owner is gone/deactivated.
     */
    public Optional<ResolvedKey> validate(String raw) {
        if (raw == null || raw.length() != FULL_LENGTH || !raw.startsWith(PREFIX)) {
            return Optional.empty();
        }
        AccessKey key = accessKeyRepository.findByKeyPrefix(raw.substring(0, PREFIX_LENGTH)).orElse(null);
        if (key == null) {
            return Optional.empty();
        }
        // Constant-time compare — never branch on partial hash equality.
        String presentedHash = sha256Hex(raw);
        if (!MessageDigest.isEqual(
            presentedHash.getBytes(StandardCharsets.UTF_8),
            key.getKeyHash().getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        if (!key.isUsable(now)) {
            return Optional.empty();
        }
        AppUser owner = userRepository.findByIdWithMember(key.getCreatedBy()).orElse(null);
        if (owner == null || !owner.isActivated()) {
            return Optional.empty();
        }
        recordUsage(key.getId(), now);
        return Optional.of(new ResolvedKey(owner, key.getScopes(), key.getId()));
    }

    public List<AccessKey> list(Long memberId) {
        return accessKeyRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    /** Member-scoped revoke. True if the key is (now or already) revoked; false if not the caller's key. */
    @Transactional
    public boolean revoke(Long id, Long memberId) {
        AccessKey key = accessKeyRepository.findByIdAndMemberId(id, memberId).orElse(null);
        if (key == null) {
            return false;
        }
        if (key.getRevokedAt() != null) {
            return true;
        }
        key.setRevokedAt(Instant.now(clock));
        accessKeyRepository.save(key);
        return true;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void validateScopes(Set<String> scopes) {
        for (String scope : scopes) {
            if (!Scopes.ALL.contains(scope)) {
                throw new IllegalArgumentException("Unknown scope: " + scope);
            }
        }
    }

    /** Persist last_used_at at most once per key per throttle window; best-effort, never breaks auth. */
    private void recordUsage(Long keyId, Instant now) {
        Instant last = lastTouch.get(keyId);
        if (last != null && Duration.between(last, now).getSeconds() < touchThrottleSeconds) {
            return;
        }
        lastTouch.put(keyId, now);
        try {
            usageRecorder.touch(keyId, now);
        } catch (RuntimeException ex) {
            log.warn("Failed to record access-key usage for key {}", keyId, ex);
        }
    }

    private String generateBody() {
        char[] out = new char[BODY_LENGTH];
        for (int i = 0; i < BODY_LENGTH; i++) {
            out[i] = BASE62[random.nextInt(BASE62.length)];
        }
        return new String(out);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** A newly created key and its one-time plaintext secret. */
    public record GeneratedKey(AccessKey accessKey, String rawSecret) {}

    /** The outcome of validating a presented key: the principal to act as and its scopes. */
    public record ResolvedKey(AppUser owner, Set<String> scopes, Long keyId) {}
}
