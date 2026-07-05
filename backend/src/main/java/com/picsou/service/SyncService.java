package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.*;
import com.picsou.port.BankConnectorPort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.budget.RecurringDetectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final BankConnectorPort bankConnector;
    private final AccountRepository accountRepository;
    private final RequisitionRepository requisitionRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final AccountService accountService;
    private final TransactionRepository transactionRepository;
    private final CategorizationService categorizationService;
    private final RecurringDetectionService recurringDetectionService;
    private final RevolutPocketService revolutPocketService;

    /** How far back to pull transactions on each sync; dedup makes the overlap harmless. */
    private static final int TRANSACTION_LOOKBACK_DAYS = 90;

    public SyncService(
        BankConnectorPort bankConnector,
        AccountRepository accountRepository,
        RequisitionRepository requisitionRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService,
        TransactionRepository transactionRepository,
        CategorizationService categorizationService,
        RecurringDetectionService recurringDetectionService,
        RevolutPocketService revolutPocketService
    ) {
        this.bankConnector = bankConnector;
        this.accountRepository = accountRepository;
        this.requisitionRepository = requisitionRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService = accountService;
        this.transactionRepository = transactionRepository;
        this.categorizationService = categorizationService;
        this.recurringDetectionService = recurringDetectionService;
        this.revolutPocketService = revolutPocketService;
    }

    /**
     * Re-run recurring detection for a member after a sync, isolating any failure so it never
     * rolls back the freshly-ingested balances and transactions.
     */
    private void detectRecurring(Long memberId) {
        try {
            recurringDetectionService.detect(memberId, LocalDate.now());
        } catch (Exception ex) {
            log.warn("Recurring detection skipped for member {}: {}", memberId, ex.getMessage());
        }
    }

    /**
     * Run the Revolut pocket backfill for a member after all accounts and their transactions
     * have been upserted. The backfill reconstructs pockets over the member's full history
     * (not just the 90-day sync window), so historical "To … MB" rows are cleaned up too.
     * Isolated from the enclosing sync transaction the same way {@link #detectRecurring} is —
     * a backfill failure must never roll back freshly-ingested balances.
     * <p>
     * No-op if the member has no Revolut wallet accounts.
     */
    private void runPocketBackfill(Long memberId) {
        try {
            revolutPocketService.backfillForMember(memberId);
        } catch (Exception ex) {
            log.warn("Revolut pocket backfill skipped for member {}: {}", memberId, ex.getMessage());
        }
    }

    /** Step 1: Initiate Enable Banking bank connection for a given institution. */
    public InitiateResponse initiateConnection(String institutionId, String institutionName, Long memberId) {
        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        BankConnectorPort.InitiateResult result = bankConnector.initiateConnection(institutionId);

        Requisition requisition = Requisition.builder()
            .member(member)
            .requisitionId(result.requisitionId())
            .institutionId(institutionId)
            .institutionName(institutionName)
            .logoUrl(resolveLogoUrl(institutionId, institutionName))
            .status(RequisitionStatus.CREATED)
            .authLink(result.authLink())
            .build();

        requisitionRepository.save(requisition);

        return new InitiateResponse(result.requisitionId(), result.authLink());
    }

    /** Step 2: Complete Enable Banking flow -- exchange OAuth code, fetch balances, upsert accounts. */
    @Transactional(noRollbackFor = SyncException.class)
    public List<AccountResponse> completeConnection(String oauthCode, Long memberId) {
        // Find the pending requisition for this member
        Requisition requisition = requisitionRepository
            .findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.CREATED, memberId)
            .stream().findFirst()
            .orElseThrow(() -> new SyncException("No pending bank connection found. Please initiate a new connection."));

        String sessionId;
        try {
            sessionId = bankConnector.exchangeCode(oauthCode);
        } catch (SyncException ex) {
            // Code already used -> find existing linked session and just refresh balances
            if (ex.getMessage().contains("ALREADY_AUTHORIZED")) {
                log.info("Code already used, refreshing latest linked session");
                return resyncLatest(memberId);
            }
            requisition.setStatus(RequisitionStatus.FAILED);
            requisitionRepository.save(requisition);
            throw ex;
        }

        // Store session_id so the scheduler can re-sync later
        requisition.setRequisitionId(sessionId);

        List<BankConnectorPort.AccountData> accountDataList;
        try {
            accountDataList = bankConnector.fetchBalances(sessionId);
        } catch (SyncException ex) {
            requisition.setStatus(RequisitionStatus.FAILED);
            requisitionRepository.save(requisition);
            throw ex;
        }

        FamilyMember member = requisition.getMember();

        List<AccountResponse> responses = accountDataList.stream()
            .map(data -> upsertAccount(data, requisition, member))
            .flatMap(Optional::stream)
            .toList();

        // If the bank hasn't finished linking accounts yet, leave the
        // requisition retryable (status=FAILED so the UI shows the retry
        // button). The session id is preserved, so retrySync() just refetches
        // without going back through OAuth.
        if (accountDataList.isEmpty()) {
            requisition.setStatus(RequisitionStatus.FAILED);
            requisitionRepository.save(requisition);
            log.info("Enable Banking session {} not yet populated — marking retryable", sessionId);
            return responses;
        }

        requisition.setStatus(RequisitionStatus.LINKED);
        requisition.setLastSyncedAt(Instant.now());
        requisitionRepository.save(requisition);

        detectRecurring(member.getId());
        runPocketBackfill(member.getId());

        log.info("Completed Enable Banking sync for {}: {} accounts linked", requisition.getInstitutionName(), responses.size());
        return responses;
    }

    /** Search available institutions. */
    @Transactional(readOnly = true)
    public List<BankConnectorPort.InstitutionData> searchInstitutions(String query, String country) {
        return bankConnector.searchInstitutions(query, country);
    }

    /** Get all requisitions for a member ordered by date. */
    @Transactional(readOnly = true)
    public List<Requisition> getAllRequisitions(Long memberId) {
        return requisitionRepository.findAllByMemberId(memberId);
    }

    /** Retry fetching accounts for a FAILED requisition using the stored session_id. */
    @Transactional(noRollbackFor = SyncException.class)
    public List<AccountResponse> retrySync(Long id, Long memberId) {
        Requisition req = requisitionRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Requisition not found"));

        log.info("Retrying sync for {} (session={})", req.getInstitutionName(), req.getRequisitionId());
        ensureLogoUrl(req);

        List<BankConnectorPort.AccountData> accountDataList;
        try {
            accountDataList = bankConnector.fetchBalances(req.getRequisitionId());
        } catch (SyncException ex) {
            req.setStatus(RequisitionStatus.FAILED);
            requisitionRepository.save(req);
            throw ex;
        }

        FamilyMember member = req.getMember();

        List<AccountResponse> responses = accountDataList.stream()
            .map(data -> upsertAccount(data, req, member))
            .flatMap(Optional::stream)
            .toList();

        // Mirror completeConnection(): if the bank still hasn't populated accounts,
        // keep the requisition FAILED (retryable) rather than promoting it to LINKED
        // with zero accounts — which would hide the retry button and strand the user.
        if (accountDataList.isEmpty()) {
            req.setStatus(RequisitionStatus.FAILED);
            requisitionRepository.save(req);
            log.info("Enable Banking session {} still not populated on retry — keeping retryable",
                req.getRequisitionId());
            return responses;
        }

        req.setStatus(RequisitionStatus.LINKED);
        req.setLastSyncedAt(Instant.now());
        requisitionRepository.save(req);

        detectRecurring(member.getId());
        runPocketBackfill(member.getId());

        log.info("Retry sync OK for {}: {} accounts linked", req.getInstitutionName(), responses.size());
        return responses;
    }

    /**
     * Start a fresh OAuth flow for an existing FAILED/CREATED requisition.
     * Overwrites the stored session ID and auth link in-place so the OAuth callback
     * (completeConnection) can still locate the same Requisition row by the new requisitionId.
     */
    public String reconnectBank(Long id, Long memberId) {
        Requisition req = requisitionRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Requisition not found"));

        BankConnectorPort.InitiateResult result = bankConnector.initiateConnection(req.getInstitutionId());
        req.setRequisitionId(result.requisitionId());
        req.setAuthLink(result.authLink());
        req.setStatus(RequisitionStatus.CREATED);
        req.setLastSyncedAt(null);
        requisitionRepository.save(req);

        log.info("Reconnect initiated for {} — new session {}", req.getInstitutionName(), result.requisitionId());
        return result.authLink();
    }

    /** Delete a requisition (cancel or remove a bank connection). */
    public void deleteRequisition(Long id, Long memberId) {
        Requisition req = requisitionRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Requisition not found"));
        requisitionRepository.delete(req);
        log.info("Deleted requisition {}", id);
    }

    /** Retry all FAILED Enable Banking sessions for a member (called by scheduler). */
    public void retryAllFailed(Long memberId) {
        List<Requisition> failed = requisitionRepository
            .findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.FAILED, memberId);
        for (Requisition req : failed) {
            try {
                retrySync(req.getId(), memberId);
            } catch (Exception ex) {
                log.warn("Scheduled retry failed for {} (session={}): {}",
                    req.getInstitutionName(), req.getRequisitionId(), ex.getMessage());
            }
        }
    }

    /** Re-sync all LINKED requisitions for a specific member (called by scheduler). */
    public void resyncAll(Long memberId) {
        List<Requisition> linked = requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId);
        for (Requisition req : linked) {
            try {
                ensureLogoUrl(req);
                List<BankConnectorPort.AccountData> accounts = bankConnector.fetchBalances(req.getRequisitionId());
                FamilyMember member = req.getMember();
                accounts.forEach(data -> upsertAccount(data, req, member));
                req.setLastSyncedAt(Instant.now());
                requisitionRepository.save(req);
                detectRecurring(member.getId());
                runPocketBackfill(member.getId());
                log.info("Auto-resync OK for {}: {} accounts", req.getInstitutionName(), accounts.size());
            } catch (Exception ex) {
                req.setStatus(RequisitionStatus.FAILED);
                requisitionRepository.save(req);
                log.warn("Auto-resync failed for {}: {}", req.getInstitutionName(), ex.getMessage());
            }
        }
    }

    /** Refresh balances for the most recent LINKED session for a member. */
    private List<AccountResponse> resyncLatest(Long memberId) {
        Requisition req = requisitionRepository
            .findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus.LINKED, memberId)
            .stream().findFirst()
            .orElseThrow(() -> new SyncException("No linked session found to refresh."));

        ensureLogoUrl(req);
        FamilyMember member = req.getMember();

        List<BankConnectorPort.AccountData> accountDataList = bankConnector.fetchBalances(req.getRequisitionId());
        List<AccountResponse> responses = accountDataList.stream()
            .map(data -> upsertAccount(data, req, member))
            .flatMap(Optional::stream)
            .toList();
        req.setLastSyncedAt(Instant.now());
        requisitionRepository.save(req);
        detectRecurring(member.getId());
        runPocketBackfill(member.getId());
        log.info("Refreshed {} accounts for {}", responses.size(), req.getInstitutionName());
        return responses;
    }

    // --- Private ---

    /**
     * Best-effort backfill for requisitions created before bank logos were captured
     * (or whose logo lookup missed the first time): re-searches institutions, scoped
     * to the requisition's own country, and stores the match's logo, if any.
     *
     * <p>Bounded to a single attempt per requisition via {@code logoBackfillAttemptedAt}
     * — a miss (renamed institution, no provider logo) is not retried on every
     * resync/retry forever. The marker is only set once the search call actually
     * completes, so a transient network failure can still be retried next sync.
     */
    private void ensureLogoUrl(Requisition req) {
        if (req.getLogoUrl() != null || req.getLogoBackfillAttemptedAt() != null) return;
        try {
            String country = parseCountry(req.getInstitutionId());
            List<BankConnectorPort.InstitutionData> matches = bankConnector.searchInstitutions(req.getInstitutionName(), country);
            req.setLogoBackfillAttemptedAt(Instant.now());
            findInstitution(matches, req.getInstitutionId(), req.getInstitutionName())
                .map(BankConnectorPort.InstitutionData::logoUrl)
                .ifPresent(req::setLogoUrl);
        } catch (Exception ex) {
            log.warn("Could not backfill logo for requisition {} ({}): {}", req.getId(), req.getInstitutionName(), ex.getMessage());
        }
    }

    /**
     * Resolves a bank's logo at connection-initiation time from the server's own
     * institution catalog — the client-supplied logoUrl is never trusted/persisted,
     * since nothing between an arbitrary client-supplied URL and the Accounts page
     * `<img src>` would validate its scheme or host.
     */
    private String resolveLogoUrl(String institutionId, String institutionName) {
        try {
            List<BankConnectorPort.InstitutionData> matches =
                bankConnector.searchInstitutions(institutionName, parseCountry(institutionId));
            return findInstitution(matches, institutionId, institutionName)
                .map(BankConnectorPort.InstitutionData::logoUrl)
                .orElse(null);
        } catch (Exception ex) {
            log.warn("Could not resolve logo for institution {} ({}): {}", institutionId, institutionName, ex.getMessage());
            return null;
        }
    }

    /** Matches by exact institution id first; falls back to a case-insensitive name match only if no id match exists. */
    private static Optional<BankConnectorPort.InstitutionData> findInstitution(
        List<BankConnectorPort.InstitutionData> candidates, String institutionId, String institutionName
    ) {
        return candidates.stream()
            .filter(i -> i.id().equals(institutionId))
            .findFirst()
            .or(() -> candidates.stream()
                .filter(i -> i.name().equalsIgnoreCase(institutionName))
                .findFirst());
    }

    /** institutionId format: "BankName::FR" (name::country) — see EnableBankingBankConnector. */
    private static String parseCountry(String institutionId) {
        if (institutionId == null) return null;
        String[] parts = institutionId.split("::");
        return parts.length > 1 ? parts[1] : null;
    }

    /**
     * Returns {@link Optional#empty()} when the matching account was soft-deleted
     * by the user — we must not resurrect it on the next sync. The bank may keep
     * returning the same external id forever; that's not consent to bring it back.
     *
     * <p>Matching strategy (Enable Banking v0.16.4 uid-rotation resilience):
     * <ol>
     *   <li>If the account has an IBAN, look up by {@code (iban, memberId)} first — IBAN is
     *       stable even when the provider uid changes (e.g. Boursorama after EB v0.16.4).
     *       When matched via IBAN, the stored {@code externalAccountId} is refreshed to the
     *       current uid so future syncs stay aligned.</li>
     *   <li>Fall back to {@code (externalAccountId, memberId)} for accounts without an IBAN
     *       and for providers whose uid never changes.</li>
     * </ol>
     * Soft-delete guards follow the same two-step order.
     */
    private Optional<AccountResponse> upsertAccount(BankConnectorPort.AccountData data, Requisition requisition, FamilyMember member) {
        // Step 1: locate an existing active account (IBAN-first when available)
        Optional<Account> existing = Optional.empty();
        if (data.iban() != null) {
            existing = accountRepository.findByIbanAndMemberId(data.iban(), member.getId());
        }
        if (existing.isEmpty()) {
            existing = accountRepository.findByExternalAccountIdAndMemberId(data.externalId(), member.getId());
        }

        // Step 2: soft-delete guard — refuse to resurrect an account the user removed
        if (existing.isEmpty()) {
            boolean softDeleted = (data.iban() != null &&
                accountRepository.existsSoftDeletedByIbanAndMemberId(data.iban(), member.getId()))
                || accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), member.getId());
            if (softDeleted) {
                log.info("Skipping resurrection of soft-deleted account externalId={} iban={} member={}",
                    data.externalId(), data.iban(), member.getId());
                return Optional.empty();
            }
        }

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(data.balance());
            account.setLastSyncedAt(Instant.now());
            // Refresh uid in case the provider rotated it (EB v0.16.4 Boursorama case)
            account.setExternalAccountId(data.externalId());
            if (data.iban() != null) {
                account.setIban(data.iban());
            }
            // Backfill the bank logo on a pre-logo account once the requisition has one.
            if (account.getLogoUrl() == null && requisition.getLogoUrl() != null) {
                account.setLogoUrl(requisition.getLogoUrl());
            }
        } else {
            account = Account.builder()
                .member(member)
                .name(data.name() != null ? data.name() : "Account")
                .type(AccountType.CHECKING)
                .provider(requisition.getInstitutionName())
                .currency(data.currency() != null ? data.currency() : "EUR")
                .currentBalance(data.balance())
                .lastSyncedAt(Instant.now())
                .externalAccountId(data.externalId())
                .iban(data.iban())
                .isManual(false)
                .color("#6366f1")
                .logoUrl(requisition.getLogoUrl())
                .build();
        }

        account = accountRepository.save(account);
        accountService.upsertSnapshot(account, data.balance(), LocalDate.now());

        ingestTransactions(account, requisition.getRequisitionId(), member);

        return Optional.of(accountService.toResponse(account));
    }

    /**
     * Pull recent transactions for a synced account, skipping any already stored
     * (dedup by {@code (account, externalId)}), and auto-categorize each new one via the
     * member's rules. The account's authoritative balance still comes from the balance
     * endpoint — we never recompute it from this partial transaction window. Failures here
     * are logged and swallowed so a transaction hiccup never breaks the balance sync.
     */
    private void ingestTransactions(Account account, String sessionId, FamilyMember member) {
        if (account.getExternalAccountId() == null) {
            return;
        }
        LocalDate from = LocalDate.now().minusDays(TRANSACTION_LOOKBACK_DAYS);
        List<BankConnectorPort.TransactionData> fetched;
        try {
            fetched = bankConnector.fetchTransactions(sessionId, account.getExternalAccountId(), from);
        } catch (Exception ex) {
            log.warn("Transaction ingestion skipped for account {}: {}", account.getId(), ex.getMessage());
            return;
        }

        // Load the member's rules + categories-by-slug once and reuse across the whole window
        // (no per-transaction queries); each new transaction is enriched + categorized in memory.
        CategorizationService.CategorizationContext categorization =
            categorizationService.loadContext(member.getId());

        int inserted = 0;
        for (BankConnectorPort.TransactionData data : fetched) {
            if (data.externalId() != null
                && transactionRepository.existsByAccountIdAndExternalId(account.getId(), data.externalId())) {
                continue;
            }
            Transaction tx = Transaction.builder()
                .account(account)
                .date(data.date())
                .description(data.description())
                .amount(data.amount())
                .counterparty(data.counterparty())
                .externalId(data.externalId())
                .nativeCurrency(data.currency() != null ? data.currency() : "EUR")
                .isManual(false)
                .build();
            categorizationService.autoCategorize(tx, categorization);
            transactionRepository.save(tx);
            // After persisting, detect and process Revolut pocket transfers.
            revolutPocketService.processTransaction(tx, member.getId());
            inserted++;
        }
        if (inserted > 0) {
            log.info("Ingested {} new transactions for account {}", inserted, account.getId());
        }
    }

    public record InitiateResponse(String requisitionId, String authLink) {}
}
