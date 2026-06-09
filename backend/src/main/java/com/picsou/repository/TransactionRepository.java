package com.picsou.repository;

import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByAccountIdOrderByDateDesc(Long accountId);

    Optional<Transaction> findByIdAndAccountId(Long id, Long accountId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId")
    BigDecimal sumAmountByAccountId(@Param("accountId") Long accountId);

    void deleteByAccountId(Long accountId);

    void deleteByAccountIdAndIsManualFalse(Long accountId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId AND t.date > :date")
    BigDecimal sumAmountByAccountIdAndDateAfter(@Param("accountId") Long accountId, @Param("date") LocalDate date);

    List<Transaction> findByAccountIdAndTxTypeInOrderByDateAsc(Long accountId, List<TransactionType> types);

    /** Earliest transaction date across all accounts */
    @Query("SELECT MIN(t.date) FROM Transaction t")
    LocalDate findEarliestDate();

    // ─── Budget & Cashflow (1.1.0) ────────────────────────────────────────────

    /** Dedup guard for synced ingestion. */
    boolean existsByAccountIdAndExternalId(Long accountId, String externalId);

    /** Member-scoped single transaction lookup (categorize endpoint). */
    Optional<Transaction> findByIdAndAccountMemberId(Long id, Long memberId);

    /** Transactions belonging to a member that have no managed category yet. */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef IS NULL
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> findUncategorizedByMemberId(@Param("memberId") Long memberId);

    /** All member transactions in a date range (cashflow / detection input). */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.date BETWEEN :from AND :to
        ORDER BY t.date ASC, t.id ASC
        """)
    List<Transaction> findByMemberIdAndDateBetween(@Param("memberId") Long memberId,
                                                   @Param("from") LocalDate from,
                                                   @Param("to") LocalDate to);

    /** One category's transactions for a member over a range, newest first (spending drill). */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef.id = :categoryId
        AND t.date BETWEEN :from AND :to
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> findByMemberIdAndCategoryIdAndDateBetween(@Param("memberId") Long memberId,
                                                                @Param("categoryId") Long categoryId,
                                                                @Param("from") LocalDate from,
                                                                @Param("to") LocalDate to);

    /** Sum of (signed) amounts for one category over a date range — used by envelopes. */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.categoryRef.id = :categoryId AND t.date BETWEEN :from AND :to
        """)
    BigDecimal sumByCategoryIdAndDateBetween(@Param("categoryId") Long categoryId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    /** A member's transactions across several categories over a range, newest first (parent drill). */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef.id IN :categoryIds
        AND t.date BETWEEN :from AND :to
        ORDER BY t.date DESC, t.id DESC
        """)
    List<Transaction> findByMemberIdAndCategoryIdInAndDateBetween(@Param("memberId") Long memberId,
                                                                  @Param("categoryIds") Collection<Long> categoryIds,
                                                                  @Param("from") LocalDate from,
                                                                  @Param("to") LocalDate to);

    /** Sum of (signed) amounts across several categories over a range — parent envelope rollup. */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.categoryRef.id IN :categoryIds AND t.date BETWEEN :from AND :to
        """)
    BigDecimal sumByCategoryIdInAndDateBetween(@Param("categoryIds") Collection<Long> categoryIds,
                                               @Param("from") LocalDate from,
                                               @Param("to") LocalDate to);

    /** Sum of (signed) amounts for a member, filtered by category kind, over a range. */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef.kind = :kind
        AND t.date BETWEEN :from AND :to
        """)
    BigDecimal sumByMemberIdAndKindAndDateBetween(@Param("memberId") Long memberId,
                                                  @Param("kind") com.picsou.model.CategoryKind kind,
                                                  @Param("from") LocalDate from,
                                                  @Param("to") LocalDate to);

    /** Member transactions with a given category kind in a range (allocation flux). */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.member.id = :memberId AND t.categoryRef.kind = :kind
        AND t.date BETWEEN :from AND :to
        """)
    List<Transaction> findByMemberIdAndKindAndDateBetween(@Param("memberId") Long memberId,
                                                          @Param("kind") com.picsou.model.CategoryKind kind,
                                                          @Param("from") LocalDate from,
                                                          @Param("to") LocalDate to);

    /** Detach all transactions from a category before it is hard-removed/archived. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Transaction t SET t.categoryRef = NULL WHERE t.categoryRef.id = :categoryId")
    void clearCategory(@Param("categoryId") Long categoryId);
}
