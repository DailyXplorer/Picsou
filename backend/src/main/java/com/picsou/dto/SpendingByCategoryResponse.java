package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Expense totals grouped by category over a span — the ranked breakdown behind the
 * spending drill-down. Amounts are positive magnitudes; {@code share} is the fraction
 * of {@code totalExpense} (0–1). The {@code categoryId == null} row, when present,
 * collects spending that has no managed category yet. Transfers are excluded.
 */
public record SpendingByCategoryResponse(
    CashflowPeriod period,
    LocalDate from,
    LocalDate to,
    BigDecimal totalExpense,
    List<CategorySpend> categories
) {
    public record CategorySpend(
        Long categoryId,
        String slug,
        String name,
        String color,
        String icon,
        BigDecimal amount,
        int count,
        BigDecimal share
    ) {}
}
