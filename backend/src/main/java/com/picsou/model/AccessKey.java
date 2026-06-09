package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.mcp.ScopeSetConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A scoped access-key that lets an external app (an MCP client) act on ONE member's data,
 * over {@code /mcp/**} only. The raw secret is shown once at creation; only its SHA-256
 * ({@link #keyHash}) is stored. Mirrors {@link Goal}'s member-scoping and
 * {@link PersistentSession}'s active-window predicate.
 */
@Entity
@Table(name = "access_key")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"keyHash", "member"})   // never log the secret hash; never lazy-load member
public class AccessKey extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The member whose data this key may act on. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    /** The login that created the key; also the principal resolved on the auth hot path. */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(nullable = false, length = 100)
    private String name;

    /** "psk_" + 8 random chars; unique, used for O(1) lookup before the constant-time hash compare. */
    @Column(name = "key_prefix", nullable = false, length = 16, unique = true)
    private String keyPrefix;

    /** SHA-256 hex (64 chars) of the full secret. Never serialized, never logged. */
    @JsonIgnore
    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    /** Granted scopes, persisted as a single space-delimited column via {@link ScopeSetConverter}. */
    @Convert(converter = ScopeSetConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private Set<String> scopes = new LinkedHashSet<>();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** Optional expiry; {@code null} = never expires. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Usable iff not revoked and not past its (optional) expiry. */
    public boolean isUsable(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
