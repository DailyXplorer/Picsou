package com.picsou.mcp;

import com.picsou.repository.AccessKeyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Isolated, best-effort writer for an access-key's {@code last_used_at} stamp.
 *
 * <p>Lives in its own bean (not a private method of {@link AccessKeyService}) for two reasons:
 * the {@code @Transactional} proxy only applies across bean boundaries, and {@code REQUIRES_NEW}
 * gives the stamp its own transaction so it commits independently of — and can never roll back —
 * the request that triggered it.
 */
@Component
public class AccessKeyUsageRecorder {

    private final AccessKeyRepository repository;

    public AccessKeyUsageRecorder(AccessKeyRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touch(Long keyId, Instant ts) {
        repository.touchLastUsedAt(keyId, ts);
    }
}
