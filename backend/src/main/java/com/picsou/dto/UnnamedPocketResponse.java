package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Describes a Revolut pocket that still carries its placeholder name (not yet named by the user).
 * Returned by {@code GET /api/revolut-pockets/unnamed} so the frontend can render the naming popup:
 * the transfer list lets the user recognize the pocket ("the one that received 666 € and 108 €").
 */
public record UnnamedPocketResponse(
    /** Account id of the pocket — used to call {@code PUT /accounts/{id}} to rename it. */
    Long accountId,
    /** Current placeholder name, e.g. {@code "Pocket ••89abfe"}. */
    String placeholderName,
    /** Parent wallet account id. */
    Long parentAccountId,
    /** Recent inflow transfers into this pocket, newest first. */
    List<PocketTransfer> transfers
) {
    /** One inflow leg recorded for this pocket. */
    public record PocketTransfer(LocalDate date, BigDecimal amount) {}
}
