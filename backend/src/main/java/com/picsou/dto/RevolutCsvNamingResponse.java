package com.picsou.dto;

import java.util.List;

/**
 * Result of uploading a Revolut CSV export for pocket naming.
 * Contains one suggestion per detected pocket uuid, with an uncertainty flag when the
 * same (amount, date) pair matches more than one pocket (ambiguous reconciliation).
 * <p>
 * <strong>Never auto-applied</strong> — the user reviews and confirms each name.
 */
public record RevolutCsvNamingResponse(List<PocketNameSuggestion> suggestions) {

    /** One suggested name for a pocket, potentially marked ambiguous. */
    public record PocketNameSuggestion(
        /** Account id of the pocket to rename. */
        Long accountId,
        /** Suggested human-readable name parsed from the CSV. */
        String suggestedName,
        /**
         * True when the (amount, date) reconciliation matched more than one pocket:
         * the name may belong to any of them — the user must confirm.
         */
        boolean uncertain
    ) {}
}
