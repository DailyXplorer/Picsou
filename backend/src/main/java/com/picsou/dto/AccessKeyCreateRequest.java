package com.picsou.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;

/**
 * Request to mint a new MCP access-key for the authenticated member.
 *
 * <p>{@code scopes} must each be a member of {@link com.picsou.mcp.Scopes#ALL}; the service rejects an
 * unknown scope with {@link IllegalArgumentException}, which the global handler maps to HTTP 400.
 * {@code expiresAt} is optional ({@code null} = never expires) and, when present, must be in the future.
 */
public record AccessKeyCreateRequest(
    @NotBlank @Size(max = 100) String name,
    @NotEmpty Set<String> scopes,
    @Future Instant expiresAt
) {}
