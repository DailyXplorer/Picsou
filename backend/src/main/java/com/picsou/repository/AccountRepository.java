package com.picsou.repository;

import com.picsou.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findAllByMemberIdOrderByCreatedAtAsc(Long memberId);
    Optional<Account> findByIdAndMemberId(Long id, Long memberId);

    /**
     * Member-scoped batch lookup by id. Used when resolving a caller-supplied list of
     * account ids (e.g. goal membership) so accounts belonging to another member are
     * never returned — closing an IDOR where {@code findAllById} ignored ownership.
     */
    List<Account> findByIdInAndMemberId(List<Long> ids, Long memberId);
    Optional<Account> findByExternalAccountIdAndMemberId(String externalAccountId, Long memberId);
    List<Account> findByTickerIsNotNullAndMemberId(Long memberId);

    /**
     * Returns true if any soft-deleted account exists with this external id for the member.
     * Bypasses {@code @SQLRestriction("deleted_at IS NULL")} on Account.
     * Used by sync upserts to refuse resurrecting accounts the user explicitly removed.
     */
    @Query(value =
        "SELECT EXISTS(SELECT 1 FROM account " +
        "  WHERE external_account_id = :externalId AND member_id = :memberId AND deleted_at IS NOT NULL)",
        nativeQuery = true)
    boolean existsSoftDeletedByExternalAccountIdAndMemberId(
        @Param("externalId") String externalId,
        @Param("memberId") Long memberId
    );

    /**
     * Find an active account by IBAN + member. Used as a stable match key when the
     * provider's uid changes (e.g. Enable Banking v0.16.4 identification hash rotation).
     * Only called when data.iban() is non-null; bypassed for accounts without an IBAN.
     */
    Optional<Account> findByIbanAndMemberId(String iban, Long memberId);

    /**
     * Returns true if any soft-deleted account exists with this IBAN for the member.
     * Bypasses {@code @SQLRestriction("deleted_at IS NULL")} on Account.
     * Companion to {@link #existsSoftDeletedByExternalAccountIdAndMemberId} for the IBAN path.
     */
    @Query(value =
        "SELECT EXISTS(SELECT 1 FROM account " +
        "  WHERE iban = :iban AND member_id = :memberId AND deleted_at IS NOT NULL)",
        nativeQuery = true)
    boolean existsSoftDeletedByIbanAndMemberId(
        @Param("iban") String iban,
        @Param("memberId") Long memberId
    );

    // ─── Revolut pockets (1.1.0) ──────────────────────────────────────────────

    /**
     * Find all Revolut wallet accounts for a member (provider = 'Revolut', no parent account).
     * Pocket sub-accounts are excluded (they carry a parent_account_id).
     */
    @Query("""
        SELECT a FROM Account a
        WHERE a.member.id = :memberId AND a.provider = 'Revolut'
        AND a.parentAccountId IS NULL
        """)
    List<Account> findRevolutWalletsByMemberId(@Param("memberId") Long memberId);

    /**
     * Find an existing Revolut pocket sub-account by its stable uuid (external_account_id)
     * and parent wallet id. Used for idempotent find-or-create.
     */
    @Query("""
        SELECT a FROM Account a
        WHERE a.member.id = :memberId
        AND a.parentAccountId = :parentAccountId
        AND a.externalAccountId = :pocketUuid
        """)
    java.util.Optional<Account> findPocketByParentAndUuid(
        @Param("memberId") Long memberId,
        @Param("parentAccountId") Long parentAccountId,
        @Param("pocketUuid") String pocketUuid
    );

    /**
     * Returns true if a soft-deleted pocket sub-account exists for this parent + uuid.
     * Bypasses {@code @SQLRestriction("deleted_at IS NULL")} on Account.
     * Used by pocket reconstruction to refuse resurrecting pockets the user explicitly removed.
     */
    @Query(value =
        "SELECT EXISTS(SELECT 1 FROM account " +
        "  WHERE member_id = :memberId AND parent_account_id = :parentAccountId " +
        "  AND external_account_id = :pocketUuid AND deleted_at IS NOT NULL)",
        nativeQuery = true)
    boolean existsSoftDeletedPocketByParentAndUuid(
        @Param("memberId") Long memberId,
        @Param("parentAccountId") Long parentAccountId,
        @Param("pocketUuid") String pocketUuid
    );

    /**
     * All pocket sub-accounts for a member: parent_account_id IS NOT NULL.
     */
    @Query("""
        SELECT a FROM Account a
        WHERE a.member.id = :memberId AND a.parentAccountId IS NOT NULL
        ORDER BY a.parentAccountId ASC, a.id ASC
        """)
    List<Account> findAllPocketsByMemberId(@Param("memberId") Long memberId);

    /**
     * Lifts soft-delete tombstones for all Trade Republic accounts of a member.
     * Called on explicit re-authentication (completeAuth) so the upcoming sync can
     * find and update — rather than skip — previously-deleted accounts.
     * The scheduler-driven anti-resurrection guard in upsertAccount is intentionally
     * bypassed here because the user has actively chosen to reconnect.
     */
    @Modifying
    @Query(value =
        "UPDATE account SET deleted_at = NULL " +
        "WHERE member_id = :memberId AND provider = 'Trade Republic' AND deleted_at IS NOT NULL",
        nativeQuery = true)
    void restoreSoftDeletedTrAccounts(@Param("memberId") Long memberId);

    /**
     * Lifts soft-delete tombstones for all Revolut accounts of a member (wallets, pockets,
     * vaults). Called on explicit re-enrolment ({@code RevolutSyncService.completeEnrolment})
     * so the upcoming sync can find and update -- rather than skip -- previously-deleted
     * accounts. Mirrors {@link #restoreSoftDeletedTrAccounts}.
     */
    @Modifying
    @Query(value =
        "UPDATE account SET deleted_at = NULL " +
        "WHERE member_id = :memberId AND provider = 'Revolut' AND deleted_at IS NOT NULL",
        nativeQuery = true)
    void restoreSoftDeletedRevolutAccounts(@Param("memberId") Long memberId);

    /**
     * Lifts the soft-delete tombstone for a single Revolut account (by external id or, if
     * present, IBAN), mirroring the OR key used by the existence checks above. Used by the
     * manual sync + selection confirm path ({@code RevolutSyncService.confirmSync}) to restore
     * only the accounts the member explicitly selected, rather than the blanket per-provider
     * restore in {@link #restoreSoftDeletedRevolutAccounts} used on full reconnect.
     */
    @Modifying
    @Query(value =
        "UPDATE account SET deleted_at = NULL " +
        "WHERE member_id = :memberId AND provider = 'Revolut' AND deleted_at IS NOT NULL " +
        "AND (external_account_id = :externalId OR (:iban IS NOT NULL AND iban = :iban))",
        nativeQuery = true)
    void restoreSoftDeletedRevolutAccount(
        @Param("memberId") Long memberId,
        @Param("externalId") String externalId,
        @Param("iban") String iban
    );
}
