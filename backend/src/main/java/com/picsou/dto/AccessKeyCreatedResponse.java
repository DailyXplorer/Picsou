package com.picsou.dto;

/**
 * Returned exactly once, at creation: the one-time plaintext {@code secret} the caller must copy now
 * (it is never retrievable again — only its SHA-256 is stored) plus the persisted key's safe metadata.
 */
public record AccessKeyCreatedResponse(
    String secret,
    AccessKeyResponse key
) {}
