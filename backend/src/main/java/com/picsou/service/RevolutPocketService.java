package com.picsou.service;

import com.picsou.dto.RevolutCsvNamingResponse;
import com.picsou.dto.RevolutCsvNamingResponse.PocketNameSuggestion;
import com.picsou.dto.UnnamedPocketResponse;
import com.picsou.dto.UnnamedPocketResponse.PocketTransfer;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Category;
import com.picsou.model.FamilyMember;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Revolut internal pocket transfers ({@code To EUR MB:<uuid>} rows) on the main wallet,
 * reconstructs each pocket as a sub-account, reclassifies the wallet debit to
 * {@code virement-interne}, and mirrors a credit leg into the pocket. Both legs carry
 * {@code CategoryKind.TRANSFER}, removing the wallet debit from cashflow spending.
 *
 * <p>Idempotency: the mirror leg's {@code externalId} is deterministically derived from the
 * source row — re-sync or backfill never duplicates legs.
 *
 * <p>Placeholder detection: a pocket is considered "unnamed" when its name starts with
 * {@code "Pocket ••"} — the prefix used when creating it from the uuid.
 */
@Service
@Transactional
public class RevolutPocketService {

    private static final Logger log = LoggerFactory.getLogger(RevolutPocketService.class);

    /**
     * Revolut MB-transfer pattern: {@code To EUR MB:<uuid>} (case-insensitive).
     * Named group {@code uuid} captures the Revolut-internal pocket identifier.
     */
    static final Pattern POCKET_PATTERN =
        Pattern.compile("(?i)^to\\s+[a-z]{3}\\s+mb:(?<uuid>[0-9a-f\\-]{8,})$");

    /** Prefix used to flag a still-unconfirmed pocket name. */
    static final String PLACEHOLDER_PREFIX = "Pocket ••";

    /** Prefix for the mirror leg's external_id — forms a stable dedup key. */
    private static final String MIRROR_EXTERNAL_ID_PREFIX = "pocket-mirror:";

    /** How many recent inflow transfers to include in the unnamed-pockets listing. */
    private static final int TRANSFERS_PER_POCKET = 10;

    private static final String VIREMENT_INTERNE_SLUG = "virement-interne";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final CategorizationService categorizationService;

    public RevolutPocketService(
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository familyMemberRepository,
        CategorizationService categorizationService
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.categorizationService = categorizationService;
    }

    // ─── Detection ────────────────────────────────────────────────────────────

    /**
     * Try to extract a Revolut pocket uuid from a transaction description.
     * Returns the uuid string, or {@link Optional#empty()} if the description does not match.
     */
    public Optional<String> detect(String description) {
        if (description == null) {
            return Optional.empty();
        }
        Matcher m = POCKET_PATTERN.matcher(description.trim());
        return m.matches() ? Optional.of(m.group("uuid")) : Optional.empty();
    }

    // ─── Ingest hook ─────────────────────────────────────────────────────────

    /**
     * Called from {@code SyncService.ingestTransactions} after a transaction is persisted.
     * If the row matches the pocket pattern, performs the three-step reconstruction:
     * find/create pocket, reclassify wallet debit, mirror credit into pocket.
     */
    public void processTransaction(Transaction walletTx, Long memberId) {
        Optional<String> maybeUuid = detect(walletTx.getDescription());
        if (maybeUuid.isEmpty()) {
            return;
        }
        String pocketUuid = maybeUuid.get();
        Account walletAccount = walletTx.getAccount();

        Category virementInterne = resolveVirementInterne(memberId);
        if (virementInterne == null) {
            log.warn("virement-interne category not found for member {}; skipping pocket reclassification", memberId);
            return;
        }

        // 1. Find or create the pocket sub-account keyed by uuid.
        Account pocket = findOrCreatePocket(pocketUuid, walletAccount, memberId);

        // 2. Reclassify the wallet debit (unconditional — even over a prior manual category).
        walletTx.setCategoryRef(virementInterne);
        transactionRepository.save(walletTx);

        // 3. Mirror: a +amount credit leg into the pocket (idempotent by external_id).
        String mirrorExternalId = MIRROR_EXTERNAL_ID_PREFIX +
            (walletTx.getExternalId() != null ? walletTx.getExternalId() : walletTx.getId());
        boolean alreadyExists = transactionRepository
            .existsByAccountIdAndExternalId(pocket.getId(), mirrorExternalId);
        if (!alreadyExists) {
            Transaction mirror = Transaction.builder()
                .account(pocket)
                .date(walletTx.getDate())
                .description(walletTx.getDescription())
                .amount(walletTx.getAmount().negate()) // debit on wallet → credit in pocket
                .categoryRef(virementInterne)
                .externalId(mirrorExternalId)
                .nativeCurrency(walletTx.getNativeCurrency())
                .isManual(false)
                .build();
            transactionRepository.save(mirror);
            log.debug("Created mirror leg {} for pocket {}", mirrorExternalId, pocket.getId());
        }
    }

    // ─── Backfill ─────────────────────────────────────────────────────────────

    /**
     * One-time backfill over all existing Revolut wallet transactions for a member.
     * Re-running is safe (idempotent): already-processed rows are re-classified in-place;
     * mirror legs already keyed by {@code pocket-mirror:<source>} are skipped.
     */
    public void backfillForMember(Long memberId) {
        List<Account> wallets = accountRepository.findRevolutWalletsByMemberId(memberId);
        if (wallets.isEmpty()) {
            return;
        }
        log.info("Starting Revolut pocket backfill for member {} ({} wallets)", memberId, wallets.size());
        int processed = 0;
        for (Account wallet : wallets) {
            List<Transaction> txs = transactionRepository.findByAccountIdOrderByDateAsc(wallet.getId());
            for (Transaction tx : txs) {
                if (detect(tx.getDescription()).isPresent()) {
                    processTransaction(tx, memberId);
                    processed++;
                }
            }
        }
        log.info("Revolut pocket backfill done for member {}: {} transactions processed", memberId, processed);
    }

    // ─── Unnamed pockets listing ──────────────────────────────────────────────

    /**
     * Returns all pocket sub-accounts for a member that still carry the placeholder name,
     * along with their most recent inflow transfers. Used to drive the naming popup.
     */
    @Transactional(readOnly = true)
    public List<UnnamedPocketResponse> listUnnamed(Long memberId) {
        List<UnnamedPocketResponse> result = new ArrayList<>();
        for (Account pocket : accountRepository.findAllPocketsByMemberId(memberId)) {
            if (!isPlaceholder(pocket.getName())) {
                continue;
            }
            List<Transaction> inflows = transactionRepository.findTopByAccountIdOrderByDateDesc(
                pocket.getId(), PageRequest.of(0, TRANSFERS_PER_POCKET));
            List<PocketTransfer> transfers = inflows.stream()
                .map(t -> new PocketTransfer(t.getDate(), t.getAmount()))
                .toList();
            result.add(new UnnamedPocketResponse(
                pocket.getId(),
                pocket.getName(),
                pocket.getParentAccountId(),
                transfers
            ));
        }
        return result;
    }

    // ─── CSV naming ───────────────────────────────────────────────────────────

    /**
     * Parse a Revolut CSV export and reconcile pocket names by matching transfer amount + date.
     * Returns one suggestion per unnamed pocket; marks suggestions as uncertain when the same
     * (amount, date) matches more than one pocket (ambiguous). Never auto-applies names.
     */
    @Transactional(readOnly = true)
    public RevolutCsvNamingResponse namePocketsFromCsv(InputStream csvStream, Long memberId)
            throws IOException {
        List<CsvRow> csvRows = parseCsv(csvStream);
        List<Account> unnamedPockets = accountRepository.findAllPocketsByMemberId(memberId)
            .stream().filter(p -> isPlaceholder(p.getName())).toList();

        if (unnamedPockets.isEmpty() || csvRows.isEmpty()) {
            return new RevolutCsvNamingResponse(List.of());
        }

        // Build a map of (amount, date) → matched pocket (or mark ambiguous)
        // For each unnamed pocket, collect its inflows keyed by (amount, date)
        Map<Long, Map<AmountDate, Boolean>> pocketInflows = new HashMap<>();
        for (Account pocket : unnamedPockets) {
            Map<AmountDate, Boolean> byAmountDate = new HashMap<>();
            for (Transaction t : transactionRepository.findByAccountIdOrderByDateAsc(pocket.getId())) {
                byAmountDate.put(new AmountDate(t.getAmount(), t.getDate()), Boolean.TRUE);
            }
            pocketInflows.put(pocket.getId(), byAmountDate);
        }

        List<PocketNameSuggestion> suggestions = new ArrayList<>();
        for (Account pocket : unnamedPockets) {
            String bestName = null;
            boolean uncertain = false;
            Map<AmountDate, Boolean> myInflows = pocketInflows.get(pocket.getId());

            for (CsvRow row : csvRows) {
                AmountDate key = new AmountDate(row.amount(), row.date());
                if (!myInflows.containsKey(key)) {
                    continue;
                }
                // Check if another pocket also has this same (amount, date) inflow
                boolean ambiguous = unnamedPockets.stream()
                    .filter(other -> !other.getId().equals(pocket.getId()))
                    .anyMatch(other -> pocketInflows.get(other.getId()).containsKey(key));
                if (ambiguous) {
                    uncertain = true;
                }
                if (row.description() != null && !row.description().isBlank()) {
                    bestName = row.description().trim();
                    break; // take the first match
                }
            }

            if (bestName != null) {
                suggestions.add(new PocketNameSuggestion(pocket.getId(), bestName, uncertain));
            }
        }
        return new RevolutCsvNamingResponse(suggestions);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private Account findOrCreatePocket(String pocketUuid, Account walletAccount, Long memberId) {
        return accountRepository
            .findPocketByParentAndUuid(memberId, walletAccount.getId(), pocketUuid)
            .orElseGet(() -> {
                String suffix = pocketUuid.length() >= 6
                    ? pocketUuid.substring(pocketUuid.length() - 6)
                    : pocketUuid;
                FamilyMember member = familyMemberRepository.getReferenceById(memberId);
                Account pocket = Account.builder()
                    .member(member)
                    .name(PLACEHOLDER_PREFIX + suffix)
                    .type(AccountType.CHECKING)
                    .provider("Revolut")
                    .currency(walletAccount.getCurrency())
                    .externalAccountId(pocketUuid)
                    .parentAccountId(walletAccount.getId())
                    .isManual(false)
                    .color("#6366f1")
                    .build();
                Account saved = accountRepository.save(pocket);
                log.info("Created Revolut pocket sub-account {} (parent={}, uuid={})",
                    saved.getId(), walletAccount.getId(), pocketUuid);
                return saved;
            });
    }

    private Category resolveVirementInterne(Long memberId) {
        return categorizationService.categoriesBySlug(memberId).get(VIREMENT_INTERNE_SLUG);
    }

    /**
     * A pocket is considered "unnamed" (still a placeholder) when its name starts with
     * {@link #PLACEHOLDER_PREFIX}.
     */
    public static boolean isPlaceholder(String name) {
        return name != null && name.startsWith(PLACEHOLDER_PREFIX);
    }

    // ─── CSV parsing ──────────────────────────────────────────────────────────

    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Minimal Revolut CSV parser: expects a header row, then data rows with at least
     * date (col 0), description (col 1), and amount (col 4 or 5 — Revolut format).
     * Rows that cannot be parsed are silently skipped.
     */
    private List<CsvRow> parseCsv(InputStream stream) throws IOException {
        List<CsvRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String header = reader.readLine(); // skip header
            if (header == null) return rows;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = line.split(",", -1);
                if (cols.length < 5) continue;
                try {
                    LocalDate date = LocalDate.parse(cols[0].trim().replace("\"", ""), CSV_DATE);
                    String description = cols[1].trim().replace("\"", "");
                    // Revolut CSV: amount is in column 4 (outflow negative, inflow positive)
                    String rawAmount = cols[4].trim().replace("\"", "").replace(" ", "");
                    if (rawAmount.isBlank()) continue;
                    BigDecimal amount = new BigDecimal(rawAmount);
                    rows.add(new CsvRow(date, description, amount));
                } catch (Exception ignored) {
                    // skip malformed rows
                }
            }
        }
        return rows;
    }

    private record CsvRow(LocalDate date, String description, BigDecimal amount) {}

    private record AmountDate(BigDecimal amount, LocalDate date) {
        // equals/hashCode derived from record components (exact decimal match)
    }
}
