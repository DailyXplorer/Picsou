package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.model.TransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the instrument fields (ticker, display name, description) of a transaction
 * carrying a ticker or ISIN. Single source of truth shared by the
 * manual-entry service and the CSV importer, so an ISIN row and the equivalent ticker
 * row always collapse to the same position.
 *
 * <p>When the input is an ISIN it is resolved (via OpenFIGI) to a Yahoo ticker + display
 * name, so an ISIN entry and the equivalent ticker entry merge into one position and Yahoo
 * pricing works. A user-supplied {@code name} always wins over the resolved one. The
 * description is owned here so a raw ISIN never surfaces in the row.
 */
@Component
@RequiredArgsConstructor
public class InstrumentFieldResolver {

    private final OpenFigiIsinConverter openFigiIsinConverter;

    public record ResolvedInstrument(String ticker, String name, String description) {}

    /**
     * Resolves the instrument fields for a transaction carrying a ticker or ISIN.
     *
     * @param tickerOrIsin the raw ticker or ISIN input
     * @param userName     an optional user-supplied display name (wins over the resolved one)
     * @param type         the transaction type, used to build the fallback description
     * @return the resolved fields, or {@code null} when the input is blank (a cash transaction,
     *         for which the caller keeps its own description/ticker/name).
     */
    public ResolvedInstrument resolve(String tickerOrIsin, String userName, TransactionType type) {
        if (tickerOrIsin == null || tickerOrIsin.isBlank()) {
            return null; // cash transaction — no instrument
        }

        String resolvedTicker;
        String resolvedName;
        if (OpenFigiIsinConverter.isIsin(tickerOrIsin)) {
            OpenFigiIsinConverter.TickerResult r = openFigiIsinConverter.resolve(tickerOrIsin);
            resolvedTicker = r.ticker();   // already falls back to the ISIN itself on failure
            resolvedName = r.name();
        } else {
            resolvedTicker = tickerOrIsin.trim().toUpperCase();
            resolvedName = null;
        }

        String effectiveName = (userName != null && !userName.isBlank())
            ? userName.trim()
            : resolvedName;

        String fallbackLabel = type == null ? "Achat" : switch (type) {
            case DEPOSIT, WITHDRAWAL, BUY -> "Achat";
            case SELL -> "Vente";
            case DIVIDEND -> "Dividende";
            case FEE -> "Frais";
        };

        String description = effectiveName != null
            ? effectiveName
            : fallbackLabel + " " + resolvedTicker;

        return new ResolvedInstrument(resolvedTicker, effectiveName, description);
    }
}
