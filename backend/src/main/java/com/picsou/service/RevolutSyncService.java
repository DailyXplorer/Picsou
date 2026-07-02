package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.FamilyMember;
import com.picsou.model.RevolutSession;
import com.picsou.model.Transaction;
import com.picsou.port.RevolutPort;
import com.picsou.port.RevolutPort.RevolutAccountData;
import com.picsou.port.RevolutPort.RevolutTxn;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RevolutSessionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates the Revolut sidecar connector: stores the one-time enrolment {@code storageState}
 * (encrypted at rest), and drives sync/resync through the {@link RevolutPort}.
 *
 * <p>Unlike Trade Republic, there is no auth handshake here -- enrolment is an ASSISTED headful
 * login the user performs directly in the sidecar's browser (see
 * docs/features/revolut-sidecar.md §3.5); this service only receives the resulting storageState
 * via {@code completeEnrolment} and hands it back to the sidecar on every subsequent sync.
 *
 * <p>Revolut is the <b>primary</b> source for Revolut assets; Enable Banking stays connected as a
 * <b>fallback</b> for the current account (dedup by IBAN in {@link #upsertAccount}, mirroring
 * {@code SyncService.upsertAccount}). Per the spec's rate-limit rule (§3.5), auto-sync
 * ({@link #resyncIfSessionActive}) must never loop or retry aggressively -- a failure is logged
 * and swallowed, leaving Enable Banking to carry the gap until the next scheduled attempt.
 */
@Service
@Transactional
public class RevolutSyncService {

    private static final Logger log = LoggerFactory.getLogger(RevolutSyncService.class);

    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "revolut-sync");
        t.setDaemon(true);
        return t;
    });

    private final RevolutPort               revolutPort;
    private final RevolutSessionRepository  sessionRepository;
    private final AccountRepository         accountRepository;
    private final TransactionRepository     transactionRepository;
    private final FamilyMemberRepository    familyMemberRepository;
    private final AccountService            accountService;
    private final CategorizationService     categorizationService;
    private final CryptoEncryption          encryption;
    private final TransactionTemplate       txTemplate;

    public RevolutSyncService(
        RevolutPort revolutPort,
        RevolutSessionRepository sessionRepository,
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService,
        CategorizationService categorizationService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate
    ) {
        this.revolutPort         = revolutPort;
        this.sessionRepository   = sessionRepository;
        this.accountRepository   = accountRepository;
        this.transactionRepository = transactionRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService      = accountService;
        this.categorizationService = categorizationService;
        this.encryption           = encryption;
        this.txTemplate           = txTemplate;
    }

    // ─── Enrolment ──────────────────────────────────────────────────────────────

    /**
     * Stores the storageState captured by the sidecar's one-time assisted headful login,
     * replacing any prior session for this member, and fires sync in the background.
     * Returns immediately with the new session status.
     */
    public SessionStatusResponse completeEnrolment(String storageStatePlain, Long memberId) {
        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);

        // Refresh-cookie lifetime is unknown (httpOnly) -- conservative 24h TTL. resyncIfSessionActive
        // simply no-ops past expiry (never loops/retries), per the sidecar's rate-limit rule.
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);

        RevolutSession session = RevolutSession.builder()
            .member(member)
            .storageState(encryption.encrypt(storageStatePlain))
            .expiresAt(expiresAt)
            .build();
        session = sessionRepository.save(session);

        log.info("Revolut session stored for member {}, firing background sync", memberId);

        // Lift soft-delete tombstones so the upcoming sync can update (not skip) previously-deleted
        // accounts -- the user explicitly re-enrolled, meaning they want their Revolut accounts back.
        accountRepository.restoreSoftDeletedRevolutAccounts(memberId);

        final Long sessionId = session.getId();
        CompletableFuture.runAsync(() -> {
            try {
                txTemplate.executeWithoutResult(status -> {
                    sessionRepository.findById(sessionId); // ensure entity is loaded in this tx context
                    syncWithStorageState(storageStatePlain, memberId);
                });
                log.info("Revolut background sync complete");
            } catch (Exception ex) {
                log.error("Revolut background sync failed: {}", ex.getMessage());
            }
        }, syncExecutor);

        return new SessionStatusResponse(true, expiresAt);
    }

    // ─── Sync ───────────────────────────────────────────────────────────────────

    public List<AccountResponse> sync(Long memberId) {
        RevolutSession stored = sessionRepository.findByMemberId(memberId)
            .orElseThrow(() -> new SyncException("No Revolut session. Please connect from the Revolut page."));
        return syncWithStorageState(encryption.decrypt(stored.getStorageState()), memberId);
    }

    private List<AccountResponse> syncWithStorageState(String storageState, Long memberId) {
        try {
            List<RevolutAccountData> accounts = revolutPort.fetchAccounts(storageState);

            // Parents (wallets/vaults -- no parentExternalId) must be upserted before their pocket
            // children so a child's parentAccountId can resolve to the already-persisted parent's
            // Picsou account id.
            List<RevolutAccountData> ordered = accounts.stream()
                .sorted(Comparator.comparing(a -> a.parentExternalId() != null))
                .toList();

            CategorizationService.CategorizationContext ctx = categorizationService.loadContext(memberId);
            Map<String, Long> accountIdByExternalId = new HashMap<>();
            List<AccountResponse> responses = new ArrayList<>();
            for (RevolutAccountData data : ordered) {
                Long parentId = data.parentExternalId() != null
                    ? accountIdByExternalId.get(data.parentExternalId())
                    : null;
                upsertAccount(data, memberId, parentId, ctx).ifPresent(resp -> {
                    accountIdByExternalId.put(data.externalId(), resp.id());
                    responses.add(resp);
                });
            }
            log.info("Revolut sync complete: {} accounts updated", responses.size());
            return responses;
        } catch (SyncException e) {
            if ("SESSION_EXPIRED".equals(e.getMessage())) {
                log.warn("Revolut session expired for member {} -- clearing session. " +
                    "Enable Banking fallback remains active for the current account.", memberId);
                sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);
                throw new SyncException(
                    "Your Revolut session has expired. Please reconnect from the Revolut page.");
            }
            throw e;
        }
    }

    // ─── Session status ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SessionStatusResponse getSessionStatus(Long memberId) {
        Optional<RevolutSession> session = sessionRepository.findByMemberId(memberId);
        if (session.isEmpty()) {
            return new SessionStatusResponse(false, null);
        }
        RevolutSession s = session.get();
        boolean active = s.getExpiresAt() == null || s.getExpiresAt().isAfter(Instant.now());
        return new SessionStatusResponse(active, s.getExpiresAt());
    }

    public void clearSession(Long memberId) {
        sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);
        log.info("Revolut session cleared for member {}", memberId);
    }

    // ─── Scheduler entry point ───────────────────────────────────────────────────

    /**
     * Called by SchedulerService, before the Enable Banking resync (sidecar-primary). No-op if
     * there is no active session for this member. Never retries or loops on failure -- swallows
     * and logs, per the sidecar's rate-limit rule -- so Enable Banking always gets its turn.
     */
    public void resyncIfSessionActive(Long memberId) {
        Optional<RevolutSession> session = sessionRepository.findByMemberId(memberId);
        if (session.isEmpty()) return;

        RevolutSession s = session.get();
        if (s.getExpiresAt() != null && !s.getExpiresAt().isAfter(Instant.now())) {
            log.warn("Revolut session expired for member {} -- skipping auto-sync. Re-authenticate via the UI.", memberId);
            return;
        }

        try {
            syncWithStorageState(encryption.decrypt(s.getStorageState()), memberId);
        } catch (Exception ex) {
            log.warn("Revolut auto-sync failed for member {}: {}", memberId, ex.getMessage());
        }
    }

    // ─── Upsert ─────────────────────────────────────────────────────────────────

    /**
     * IBAN-first matching + soft-delete guard, mirroring {@code SyncService.upsertAccount}: a
     * wallet carries an IBAN and dedups against any existing Enable Banking account for the same
     * current account; pockets/vaults have no IBAN and match on {@code externalAccountId} alone.
     */
    private Optional<AccountResponse> upsertAccount(
            RevolutAccountData data, Long memberId, Long parentAccountId,
            CategorizationService.CategorizationContext ctx) {
        Optional<Account> existing = Optional.empty();
        if (data.iban() != null) {
            existing = accountRepository.findByIbanAndMemberId(data.iban(), memberId);
        }
        if (existing.isEmpty()) {
            existing = accountRepository.findByExternalAccountIdAndMemberId(data.externalId(), memberId);
        }

        if (existing.isEmpty()) {
            boolean softDeleted = (data.iban() != null
                    && accountRepository.existsSoftDeletedByIbanAndMemberId(data.iban(), memberId))
                || accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(data.externalId(), memberId);
            if (softDeleted) {
                log.info("Revolut: skipping resurrection of soft-deleted account externalId={} iban={} member={}",
                    data.externalId(), data.iban(), memberId);
                return Optional.empty();
            }
        }

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(data.balance());
            account.setLastSyncedAt(Instant.now());
            account.setExternalAccountId(data.externalId());
            if (data.iban() != null) {
                account.setIban(data.iban());
            }
            if (parentAccountId != null) {
                account.setParentAccountId(parentAccountId);
            }
        } else {
            FamilyMember member = familyMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            account = Account.builder()
                .member(member)
                .name(data.name())
                .type(data.type())
                .provider("Revolut")
                .currency(data.currency() != null ? data.currency() : "EUR")
                .currentBalance(data.balance())
                .lastSyncedAt(Instant.now())
                .externalAccountId(data.externalId())
                .iban(data.iban())
                .parentAccountId(parentAccountId)
                .isManual(false)
                .color(colorFor(data.type()))
                .build();
        }

        account = accountRepository.save(account);
        accountService.upsertSnapshot(account, data.balance(), LocalDate.now());

        ingestTransactions(account, data.txns(), ctx);

        return Optional.of(accountService.toResponse(account));
    }

    /**
     * Dedup by {@code (account, externalId)} and auto-categorize via the member's rules/brand KB,
     * mirroring {@code SyncService.ingestTransactions}. The context is loaded once per sync and
     * reused across every account to avoid re-querying rules/categories per account.
     */
    private void ingestTransactions(Account account, List<RevolutTxn> txns,
                                     CategorizationService.CategorizationContext ctx) {
        if (txns == null || txns.isEmpty()) {
            return;
        }
        int inserted = 0;
        for (RevolutTxn t : txns) {
            if (t.externalId() != null
                    && transactionRepository.existsByAccountIdAndExternalId(account.getId(), t.externalId())) {
                continue;
            }
            Transaction tx = Transaction.builder()
                .account(account)
                .date(t.date())
                .description(t.description())
                .amount(t.amount())
                .counterparty(t.counterparty())
                .externalId(t.externalId())
                .nativeCurrency(account.getCurrency())
                .isManual(false)
                .build();
            categorizationService.autoCategorize(tx, ctx);
            transactionRepository.save(tx);
            inserted++;
        }
        if (inserted > 0) {
            log.info("Ingested {} new Revolut transactions for account {}", inserted, account.getId());
        }
    }

    private String colorFor(com.picsou.model.AccountType type) {
        return switch (type) {
            case SAVINGS -> "#8b5cf6"; // purple, matches vaults elsewhere
            default      -> "#6366f1"; // indigo, matches wallets/pockets
        };
    }

    // ─── Response records ───────────────────────────────────────────────────────

    public record SessionStatusResponse(boolean isActive, Instant expiresAt) {}
}
