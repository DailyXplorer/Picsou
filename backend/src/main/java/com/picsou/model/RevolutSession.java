package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "revolut_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevolutSession extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    /**
     * Playwright storageState blob (cookies incl. httpOnly session/refresh + revo_device_id
     * binding), encrypted at rest by the service layer via {@code CryptoEncryption} -- never
     * stored in plain text, mirroring TradeRepublicSession/BoursoSession.
     */
    @Column(name = "storage_state", nullable = false, columnDefinition = "TEXT")
    private String storageState;

    /**
     * Refresh-cookie lifetime is unknown (httpOnly, not measurable ahead of time -- see
     * docs/features/revolut-sidecar.md §3.2/§9). Set conservatively by the service;
     * resyncIfSessionActive no-ops past this rather than looping retries.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;
}
