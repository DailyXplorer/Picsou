package com.picsou.config;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.model.Account;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.HoldingComputeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

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
     * Distinct ISINs resolved per boot. OpenFIGI allows 25 requests/min without an API key and
     * this runs while the application is starting, so the work is bounded rather than allowed to
     * hold up a boot behind an arbitrarily long queue of network calls. What is left over is
     * logged and picked up by the next boot.
     */
    private static final int MAX_ISINS_PER_BOOT = 25;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final OpenFigiIsinConverter isinConverter;
    private final HoldingComputeService holdingComputeService;

    public IsinTickerRepairRunner(TransactionRepository transactionRepository,
                                  AccountRepository accountRepository,
                                  OpenFigiIsinConverter isinConverter,
                                  HoldingComputeService holdingComputeService) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.isinConverter = isinConverter;
        this.holdingComputeService = holdingComputeService;
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
     * Deliberately not {@code @Transactional}: resolution is a network call per ISIN, and holding
     * one transaction open across all of them would keep a connection busy for the length of the
     * slowest provider. Each write below is atomic on its own — {@code saveAll} per ISIN, then the
     * already-transactional holdings recompute per account.
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

        List<String> batch = byIsin.keySet().stream().limit(MAX_ISINS_PER_BOOT).toList();
        if (batch.size() < byIsin.size()) {
            log.info("Repairing {} ISIN(s) this boot; the remaining {} are picked up by the next start",
                     batch.size(), byIsin.size() - batch.size());
        }

        // Read the accounts before the rename: afterwards these rows no longer carry the ISIN and
        // could not be found by it.
        List<Long> accountIds = transactionRepository.findManualAccountIdsByTickerIn(batch);

        int repaired = 0;
        for (String isin : batch) {
            OpenFigiIsinConverter.TickerResult resolved = isinConverter.resolve(isin);
            if (resolved == null || resolved.ticker() == null
                || resolved.ticker().equalsIgnoreCase(isin)) {
                log.debug("ISIN {} still does not resolve to a ticker, leaving it for the next boot", isin);
                continue;
            }

            List<Transaction> rows = byIsin.get(isin);
            for (Transaction tx : rows) {
                tx.setTicker(resolved.ticker());
                // The name is what the UI shows; only fill it when the row has none, so a name the
                // user typed themselves is never overwritten by the provider's.
                if (tx.getName() == null && resolved.name() != null) {
                    tx.setName(resolved.name());
                }
            }
            transactionRepository.saveAll(rows);
            repaired++;
            log.info("Repaired {} transaction(s): ISIN {} -> {}", rows.size(), isin, resolved.ticker());
        }

        if (repaired == 0) {
            return;
        }

        // Holdings are derived from transactions, so this is what turns the repaired tickers into
        // priceable positions. Restricted to investment accounts on the same rule the manual entry
        // and CSV import paths use: deriving holdings for anything else would give an account that
        // is valued from its balance one that is valued from positions instead.
        List<Account> accounts = accountRepository.findAllById(accountIds).stream()
            .filter(account -> account.getType() != null && account.getType().isInvestment())
            .toList();
        accounts.forEach(holdingComputeService::recomputeHoldings);
        log.info("Recomputed holdings for {} account(s) after ISIN ticker repair", accounts.size());
    }
}
