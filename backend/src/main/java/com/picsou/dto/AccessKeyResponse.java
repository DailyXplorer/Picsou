package com.picsou.dto;

import com.picsou.model.AccessKey;

import java.time.Instant;
import java.util.List;

/**
 * Safe projection of an {@link AccessKey} for the management API. Deliberately has NO field for the
 * secret or its hash — the hash is {@code @JsonIgnore}d on the entity, and this DTO simply never
 * carries it. {@code keyPrefix} is the public, non-secret identifier shown in the UI. Scopes are
 * sorted so the list renders in a stable order regardless of how they were stored.
 */
public record AccessKeyResponse(
    Long id,
    String name,
    String keyPrefix,
    List<String> scopes,
    Instant lastUsedAt,
    Instant expiresAt,
    Instant revokedAt,
    Instant createdAt
) {
    public static AccessKeyResponse from(AccessKey key) {
        return new AccessKeyResponse(
            key.getId(),
            key.getName(),
            key.getKeyPrefix(),
            key.getScopes().stream().sorted().toList(),
            key.getLastUsedAt(),
            key.getExpiresAt(),
            key.getRevokedAt(),
            key.getCreatedAt()
        );
    }
}
