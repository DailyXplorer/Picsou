package com.picsou.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the Revolut sidecar connector in its on-demand model: every {@link #sync} call
 * hands phone+passcode straight to the sidecar (which reuses a live per-member browser profile
 * session when possible, or performs an automated login with mobile push approval), then upserts
 * the harvested accounts. Java holds no standing browser session -- the durable state is a single
 * {@link RevolutSession} row per member, always upserted (with a fresh {@code lastSyncedAt}) after
 * every successful sync, doubling as "the sidecar has synced this member" marker for
 * {@code RevolutPocketService}. It only carries encrypted credentials when the member explicitly
 * opted in (see {@code remember} below); otherwise it is bookkeeping only.
 *
 * <p>Revolut is the <b>primary</b> source for Revolut assets; Enable Banking stays connected as a
 * <b>fallback</b> for the current account (dedup by IBAN in {@link #upsertAccount}, mirroring
 * {@code SyncService.upsertAccount}). Per the sidecar's rate-limit rule, auto-sync
 * ({@link #resyncIfSessionActive}) must never loop or retry aggressively -- a failure is logged
 * and swallowed, leaving Enable Banking to carry the gap until the next scheduled attempt.
 */
@Service
@Transactional
public class RevolutSyncService {

    private static final Logger log = LoggerFactory.getLogger(RevolutSyncService.class);

    private final RevolutPort              revolutPort;
    private final RevolutSessionRepository sessionRepository;
    private final AccountRepository        accountRepository;
    private final TransactionRepository    transactionRepository;
    private final FamilyMemberRepository   familyMemberRepository;
    private final AccountService           accountService;
    private final CategorizationService    categorizationService;
    private final CryptoEncryption         encryption;
    private final ObjectMapper             objectMapper;

    public RevolutSyncService(
        RevolutPort revolutPort,
        RevolutSessionRepository sessionRepository,
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService,
        CategorizationService categorizationService,
        CryptoEncryption encryption,
        ObjectMapper objectMapper
    ) {
        this.revolutPort         = revolutPort;
        this.sessionRepository   = sessionRepository;
        this.accountRepository   = accountRepository;
        this.transactionRepository = transactionRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService      = accountService;
        this.categorizationService = categorizationService;
        this.encryption           = encryption;
        this.objectMapper         = objectMapper;
    }

    // ─── Sync ───────────────────────────────────────────────────────────────────

    /**
     * On-demand sync. If {@code phoneNumber}/{@code passcode} are blank, falls back to the
     * member's remembered (decrypted) credentials -- fails clearly if none are stored. Delegates
     * to the sidecar, which reuses a live browser-profile session when possible or performs an
     * automated login (mobile push approval, up to ~5 minutes). When {@code remember} is true the
     * credentials are stored encrypted for unattended daily resync; when false, any previously
     * remembered credentials for this member are forgotten.
     */
    public List<AccountResponse> sync(Long memberId, String phoneNumber, String passcode, boolean remember) {
        String phone = phoneNumber;
        String code = passcode;
        boolean explicitCredentials = !isBlank(phoneNumber) && !isBlank(passcode);
        if (!explicitCredentials) {
            Credentials stored = loadStoredCredentials(memberId)
                .orElseThrow(() -> new SyncException(
                    "No saved Revolut credentials. Please enter your phone number and passcode."));
            phone = stored.phone();
            code = stored.passcode();
        } else {
            // Voluntary reconnect: the user explicitly typed phone+passcode (a fresh-login moment,
            // mirroring TradeRepublicSyncService.completeAuth) -- lift tombstones before syncing so
            // upsertAccount updates rather than silently skips previously-deleted accounts. Scheduled
            // resyncs (stored credentials, explicitCredentials=false) must NOT hit this branch: the
            // user's past delete intent still stands there. See
            // docs/lessons/soft-delete-resurrection-guard-voluntary-reconnect.md.
            accountRepository.restoreSoftDeletedRevolutAccounts(memberId);
        }

        List<AccountResponse> responses = doSync(phone, code, memberId);

        applyPostSyncSessionState(memberId, phone, code, remember);

        return responses;
    }

    private List<AccountResponse> doSync(String phoneNumber, String passcode, Long memberId) {
        try {
            List<RevolutAccountData> accounts = revolutPort.sync(phoneNumber, passcode, memberId);

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
                throw new SyncException(
                    "Your Revolut session has expired. Please reconnect with your phone number and passcode.");
            }
            if ("APPROVAL_TIMEOUT".equals(e.getMessage())) {
                throw new SyncException(
                    "The mobile approval was not confirmed in time. Please try again and approve the " +
                        "push notification on your phone.");
            }
            throw e;
        }
    }

    // ─── Status / disconnect ─────────────────────────────────────────────────────

    /**
     * {@code connected} is true when the member has completed at least one sidecar sync (the
     * {@link RevolutSession} row, upserted on every successful sync regardless of {@code remember})
     * or already has Revolut accounts from a past sync -- a fallback for rows predating this marker.
     */
    @Transactional(readOnly = true)
    public StatusResponse getStatus(Long memberId) {
        Optional<RevolutSession> session = sessionRepository.findByMemberId(memberId);
        boolean hasRevolutAccounts = !accountRepository.findRevolutWalletsByMemberId(memberId).isEmpty();
        boolean connected = session.isPresent() || hasRevolutAccounts;
        boolean remembered = session.map(RevolutSession::isRememberCredentials).orElse(false);
        Instant lastSyncedAt = session.map(RevolutSession::getLastSyncedAt).orElse(null);
        return new StatusResponse(connected, remembered, lastSyncedAt);
    }

    /** Forgets any remembered credentials. Accounts already synced are left untouched. */
    public void disconnect(Long memberId) {
        sessionRepository.findByMemberId(memberId).ifPresent(sessionRepository::delete);
        log.info("Revolut session cleared for member {}", memberId);
    }

    // ─── Scheduler entry point ───────────────────────────────────────────────────

    /**
     * Called by SchedulerService, before the Enable Banking resync (sidecar-primary). No-op
     * unless the member has REMEMBERED credentials -- the sidecar will then either reuse a live
     * browser profile session (free, no approval) or hit APPROVAL_TIMEOUT if that profile died in
     * the meantime. Never retries or loops on failure -- swallows and logs -- so Enable Banking
     * always gets its turn and a dead session is a harmless daily no-op.
     *
     * <p>Passes blank credentials so {@link #sync} takes its own stored-credentials fallback path
     * (which does NOT lift soft-delete tombstones) -- a scheduled resync must never be mistaken
     * for a voluntary reconnect, or it would silently resurrect accounts the user deliberately
     * deleted. See docs/lessons/soft-delete-resurrection-guard-voluntary-reconnect.md.
     */
    public void resyncIfSessionActive(Long memberId) {
        Optional<RevolutSession> session = sessionRepository.findByMemberId(memberId);
        if (session.isEmpty() || !session.get().isRememberCredentials()) {
            return;
        }

        try {
            sync(memberId, null, null, true);
        } catch (Exception ex) {
            log.warn("Revolut auto-sync failed for member {}: {}", memberId, ex.getMessage());
        }
    }

    // ─── Credentials (optional, member opt-in) ───────────────────────────────────

    private record Credentials(String phone, String passcode) {}

    private Optional<Credentials> loadStoredCredentials(Long memberId) {
        return sessionRepository.findByMemberId(memberId)
            .filter(RevolutSession::isRememberCredentials)
            .flatMap(s -> decryptCredentials(s.getCredentialsEnc()));
    }

    private Optional<Credentials> decryptCredentials(String credentialsEnc) {
        if (credentialsEnc == null) {
            return Optional.empty();
        }
        try {
            String json = encryption.decrypt(credentialsEnc);
            return Optional.of(objectMapper.readValue(json, Credentials.class));
        } catch (Exception ex) {
            log.warn("Failed to decrypt/parse stored Revolut credentials: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Applies the post-sync session state. The row is ALWAYS upserted with a fresh
     * {@code lastSyncedAt} after a successful sync, regardless of {@code remember} -- this is
     * what lets {@link RevolutPocketService} tell "the on-demand sidecar connector already
     * produced real pockets for this member" apart from "this member merely has some
     * provider='Revolut' accounts" (which can also come from Enable Banking alone). When
     * {@code remember} is true the encrypted credentials are stored/updated for unattended daily
     * resync; when false, any previously-remembered credentials are cleared, but the row itself
     * (and its {@code lastSyncedAt} marker) stays.
     */
    private void applyPostSyncSessionState(Long memberId, String phone, String passcode, boolean remember) {
        RevolutSession session = sessionRepository.findByMemberId(memberId)
            .orElseGet(() -> RevolutSession.builder().member(loadMember(memberId)).build());

        if (remember) {
            session.setCredentialsEnc(encryption.encrypt(serializeCredentials(phone, passcode)));
            session.setRememberCredentials(true);
        } else {
            session.setCredentialsEnc(null);
            session.setRememberCredentials(false);
        }
        session.setLastSyncedAt(Instant.now());
        sessionRepository.save(session);
    }

    private String serializeCredentials(String phone, String passcode) {
        try {
            return objectMapper.writeValueAsString(new Credentials(phone, passcode));
        } catch (JsonProcessingException ex) {
            throw new SyncException("Failed to store Revolut credentials.", ex);
        }
    }

    private FamilyMember loadMember(Long memberId) {
        return familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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

    public record StatusResponse(boolean connected, boolean remembered, Instant lastSyncedAt) {}
}
