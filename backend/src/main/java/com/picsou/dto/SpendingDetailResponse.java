package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One category's spending over a span, with the underlying transactions — the
 * {@code /budget/spending/:categoryId} drill page. {@code total} is the signed sum of
 * the listed transactions; {@code count} their number. Sub-category grouping arrives
 * with M4; for now {@code transactions} is the flat list for this category.
 */
public record SpendingDetailResponse(
    Long categoryId,
    String slug,
    String name,
    String color,
    String icon,
    CashflowPeriod period,
    LocalDate from,
    LocalDate to,
    BigDecimal total,
    int count,
    List<TransactionResponse> transactions
) {}
