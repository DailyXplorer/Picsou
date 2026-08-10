package com.picsou.config;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.model.Account;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.HoldingComputeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Re-resolves manual transactions still carrying a raw ISIN as their ticker.
 *
 * <p>A transaction entered by ISIN stores the <em>resolved</em> ticker, and {@code resolve()}
 * falls back to the ISIN itself when it cannot resolve one — OpenFIGI down, rate-limited (25
 * requests/min without an API key, which a first bulk entry blows through easily), or simply
 * without a Yahoo-quotable listing before {@code priceable()} existed. That fallback is then
 * persisted and never revisited: {@code YahooFinancePriceProvider.supports()} rejects ISIN-shaped
 * strings, so the holding has no price, and since an unpriced holding is excluded from its
 * account's value, an account whose every line went that way reads 0 € (GH issue #74).
 *
 * <p>Repairing them is safe by construction, which is why this needs no gate flag: the rows it
 * touches are exactly those that cannot be priced today, so a rewrite can only ever improve them.
 * When resolution still fails the row is left alone and retried on the next boot, and once no raw
 * ISIN remains the run is a single query. Synced rows are out of scope — their adapters re-resolve
 * on every sync.
 *
 * <p>Runs after {@link DataSeeder} and {@link StartupSyncService} and before
 * {@link PriceBackfillRunner} (which has the default LOWEST_PRECEDENCE), so the 12-month history
 * backfill requests the repaired tickers rather than the ISINs.
 */
@Component
@Order(2)
public class IsinTickerRepairRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IsinTickerRepairRunner.class);

    /**
     * Distinct ISINs resolved per boot. OpenFIGI allows 25 requests/min without an API key, so
     * asking for more in one pass mostly buys rate-limit errors.
     */
    private static final int MAX_ISINS_PER_BOOT = 25;

    /**
     * Wall-clock budget for the pass. An {@link ApplicationRunner} runs before
     * {@code ApplicationReadyEvent}, so every second spent here is a second the application is not
     * serving — and one resolution can block for up to ~55s when both providers time out
     * (OpenFIGI 5s, then the Yahoo probe, the search and up to three candidate probes at 10s each).
     * The budget is checked before starting each ISIN, so a pass can overrun by at most the
     * resolution already in flight. Whatever is left over is logged and retried on the next boot.
     */
    private static final Duration MAX_DURATION = Duration.ofSeconds(60);

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository accountHoldingRepository;
    private final OpenFigiIsinConverter isinConverter;
    private final HoldingComputeService holdingComputeService;
    private final TransactionTemplate transactions;

    public IsinTickerRepairRunner(TransactionRepository transactionRepository,
                                  AccountRepository accountRepository,
                                  AccountHoldingRepository accountHoldingRepository,
                                  OpenFigiIsinConverter isinConverter,
                                  HoldingComputeService holdingComputeService,
                                  PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.accountHoldingRepository = accountHoldingRepository;
        this.isinConverter = isinConverter;
        this.holdingComputeService = holdingComputeService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            repair();
        } catch (Exception ex) {
            // Same contract as StartupSyncService: a maintenance pass must never keep the
            // application from starting.
            log.error("ISIN ticker repair failed", ex);
        }
    }

    /**
     * Deliberately not {@code @Transactional} as a whole: resolution is a network call per ISIN,
     * and holding one transaction open across all of them would keep a connection busy for the
     * length of the slowest provider. Each ISIN is instead applied in its own short transaction,
     * opened after its resolution has returned — see {@link #applyRepair}.
     */
    void repair() {
        List<Transaction> candidates = transactionRepository.findManualTransactionsWithIsinLengthTicker();
        if (candidates.isEmpty()) {
            return;
        }

        // TreeMap: a stable order makes the per-boot cap deterministic, so a repair that is cut
        // short resumes where it stopped instead of re-drawing the same subset every boot.
        Map<String, List<Transaction>> byIsin = new TreeMap<>();
        for (Transaction tx : candidates) {
            if (OpenFigiIsinConverter.isIsin(tx.getTicker())) {
                byIsin.computeIfAbsent(tx.getTicker(), k -> new ArrayList<>()).add(tx);
            }
        }
        if (byIsin.isEmpty()) {
            return;
        }

        log.info("Found {} unresolved ISIN ticker(s) on manual transactions, re-resolving", byIsin.size());

        Instant deadline = Instant.now().plus(MAX_DURATION);
        int attempted = 0;

        for (Map.Entry<String, List<Transaction>> entry : byIsin.entrySet()) {
            if (attempted == MAX_ISINS_PER_BOOT || Instant.now().isAfter(deadline)) {
                log.info("Stopping after {} ISIN(s) ({}); the remaining {} are picked up by the next start",
                         attempted,
                         attempted == MAX_ISINS_PER_BOOT ? "per-boot limit" : "time budget spent",
                         byIsin.size() - attempted);
                break;
            }
            attempted++;

            String isin = entry.getKey();
            // Outside any transaction: this is the part that can block on two providers.
            OpenFigiIsinConverter.TickerResult resolved = isinConverter.resolve(isin);
            if (resolved == null || resolved.ticker() == null
                || resolved.ticker().equalsIgnoreCase(isin)) {
                log.debug("ISIN {} still does not resolve to a ticker, leaving it for the next boot", isin);
                continue;
            }

            try {
                transactions.executeWithoutResult(status -> applyRepair(isin, entry.getValue(), resolved));
            } catch (Exception ex) {
                // Guard per ISIN, like SchedulerService guards per account: one instrument that
                // cannot be written must not cost the others their repair. The rollback left this
                // ISIN on its rows, so the next boot picks it up again.
                log.error("Repair of ISIN {} failed and was rolled back; the rest of the pass continues",
                          isin, ex);
            }
        }
    }

    /**
     * Renames one ISIN's rows and rebuilds what derives from them, in a single transaction.
     *
     * <p>Atomic per ISIN rather than per pass, because a half-applied repair is not retryable: the
     * renamed rows no longer match the raw-ISIN query, so a failure between the rename and the
     * recompute would leave holdings stale for good, with nothing to find them by. Rolling back
     * puts the ISIN back on the rows, which is exactly what the next boot looks for.
     */
    private void applyRepair(String isin, List<Transaction> rows,
                             OpenFigiIsinConverter.TickerResult resolved) {
        // Read the accounts before the rename: afterwards these rows no longer carry the ISIN and
        // could not be found by it.
        List<Long> accountIds = transactionRepository.findManualAccountIdsByTickerIn(List.of(isin));

        for (Transaction tx : rows) {
            tx.setTicker(resolved.ticker());
            // The name is what the UI shows; only fill it when the row has none, so a name the
            // user typed themselves is never overwritten by the provider's.
            if (tx.getName() == null && resolved.name() != null) {
                tx.setName(resolved.name());
            }
        }
        transactionRepository.saveAll(rows);
        log.info("Repaired {} transaction(s): ISIN {} -> {}", rows.size(), isin, resolved.ticker());

        // Holdings are derived from transactions, so recomputing is what turns the repaired tickers
        // into priceable positions. Restricted to investment accounts on the same rule the manual
        // entry and CSV import paths use: deriving holdings for anything else would give an account
        // that is valued from its balance one that is valued from positions instead.
        List<Account> accounts = accountRepository.findAllById(accountIds).stream()
            .filter(account -> account.getType().isInvestment())
            .toList();
        if (accounts.isEmpty()) {
            return;
        }

        // First drop the holdings still keyed by the old ISIN. recomputeHoldings creates the row
        // for the new ticker but does not remove one whose ticker no longer appears in any
        // transaction, so without this the account would carry both: the repaired position and an
        // orphan that no provider can price, listed with a quantity and no value.
        accountHoldingRepository.deleteByAccountIdInAndTickerIn(
            accounts.stream().map(Account::getId).toList(), List.of(isin));

        accounts.forEach(holdingComputeService::recomputeHoldings);
        log.debug("Recomputed holdings for {} account(s) after repairing {}", accounts.size(), isin);
    }
}
