package com.picsou.port;

import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Abstraction over the revolut-auth sidecar (Python + FastAPI + Playwright), which owns a
 * logged-in {@code app.revolut.com} browser session and harvests the retail API at the
 * Playwright network layer -- below the app's own JS, which cannot be hooked (see
 * docs/features/revolut-sidecar.md §3.4).
 *
 * <p>Unlike {@link TradeRepublicPort}/BoursoPort, there is no {@code initiateAuth}/{@code completeAuth}
 * here: enrolment is a one-time ASSISTED headful login performed by the user directly in the
 * sidecar's browser (spec §3.5) -- Java never drives it. Java only receives the resulting
 * {@code storageState} blob via the controller's {@code /enrolment/complete} endpoint and hands
 * it back to the sidecar on every subsequent sync.
 */
public interface RevolutPort {

    /** The Playwright storageState blob (cookies incl. httpOnly session/device binding). */
    record RevolutSession(String storageState) {}

    record RevolutTxn(
        String externalId,
        LocalDate date,
        String description,
        BigDecimal amount,
        String counterparty
    ) {}

    record RevolutAccountData(
        String externalId,
        String name,
        AccountType type,
        String iban,
        BigDecimal balance,
        String currency,
        /** Non-null for pocket sub-accounts: the external id of their parent wallet. */
        String parentExternalId,
        List<RevolutTxn> txns
    ) {}

    /**
     * Restores the browser context from {@code storageState}, refreshes the ~4-min access token
     * (sidecar-internal {@code PUT /api/retail/token}), and harvests wallets + pockets +
     * money-boxes + IBAN + transactions. Returns a flat list; pockets/vaults carry
     * {@link RevolutAccountData#parentExternalId()} pointing at their wallet.
     *
     * @param storageState the decrypted Playwright storageState JSON
     * @throws com.picsou.exception.SyncException with message {@code "SESSION_EXPIRED"} when the
     *         sidecar reports the session is no longer valid (401 / {@code /logged-out}) --
     *         the caller must clear the stored session and let Enable Banking cover the gap.
     */
    List<RevolutAccountData> fetchAccounts(String storageState);
}
