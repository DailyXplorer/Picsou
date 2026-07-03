package com.picsou.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RevolutSyncService}'s on-demand sync model: pocket → account mapping with
 * parentAccountId resolution, money-box → SAVINGS, transaction dedup, IBAN-first matching against
 * an existing Enable Banking account, the credentials-remember opt-in, and the voluntary-reconnect
 * soft-delete tombstone lift (docs/lessons/soft-delete-resurrection-guard-voluntary-reconnect.md).
 */
@ExtendWith(MockitoExtension.class)
class RevolutSyncServiceTest {

    @Mock RevolutPort revolutPort;
    @Mock RevolutSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock CategorizationService categorizationService;
    @Mock CryptoEncryption encryption;
    @Spy ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks RevolutSyncService service;

    private static final Long MEMBER_ID = 21L;
    private static final String IBAN = "FR7630006000011234567890189";
    private static final String PHONE = "+33612345678";
    private static final String PASSCODE = "123456";

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private FamilyMember member() {
        return FamilyMember.builder().id(MEMBER_ID).build();
    }

    private void stubSaveAssignsIncrementingIds(AtomicLong idGen) {
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(idGen.incrementAndGet());
            }
            return a;
        });
    }

    private void stubToResponseMirrorsAccount() {
        lenient().when(accountService.toResponse(any(Account.class)))
            .thenAnswer(inv -> AccountResponse.from(inv.getArgument(0), bd("0")));
    }

    // ─── Upsert / dedup (unchanged behavior, new on-demand call signature) ───────

    /**
     * The sidecar returns a pocket before its parent wallet in the raw list; the service must
     * still upsert the wallet first (sorted by parentExternalId presence) so the pocket's saved
     * Account carries parentAccountId = the wallet's freshly-assigned Picsou account id.
     */
    @Test
    void sync_pocketGetsParentAccountIdFromWallet() {
        FamilyMember member = member();
        when(categorizationService.loadContext(MEMBER_ID))
            .thenReturn(new CategorizationService.CategorizationContext(List.of(), Map.of()));

        RevolutAccountData wallet = new RevolutAccountData(
            "wallet-1", "Revolut EUR", AccountType.CHECKING, IBAN, bd("100.00"), "EUR", null, List.of());
        RevolutAccountData pocket = new RevolutAccountData(
            "pocket-1", "Pocket ••abc123", AccountType.CHECKING, null, bd("50.00"), "EUR", "wallet-1", List.of());
        // Sidecar order is not guaranteed -- pocket listed before its wallet on purpose.
        when(revolutPort.sync(PHONE, PASSCODE, MEMBER_ID)).thenReturn(List.of(pocket, wallet));

        when(accountRepository.findByIbanAndMemberId(IBAN, MEMBER_ID)).thenReturn(Optional.empty());
        when(accountRepository.findByExternalAccountIdAndMemberId("wallet-1", MEMBER_ID)).thenReturn(Optional.empty());
        when(accountRepository.findByExternalAccountIdAndMemberId("pocket-1", MEMBER_ID)).thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByIbanAndMemberId(IBAN, MEMBER_ID)).thenReturn(false);
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(anyString(), eq(MEMBER_ID)))
            .thenReturn(false);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        stubSaveAssignsIncrementingIds(new AtomicLong(500));
        stubToResponseMirrorsAccount();

        service.sync(MEMBER_ID, PHONE, PASSCODE, false);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(2)).save(captor.capture());
        Account savedWallet = captor.getAllValues().stream()
            .filter(a -> "wallet-1".equals(a.getExternalAccountId())).findFirst().orElseThrow();
        Account savedPocket = captor.getAllValues().stream()
            .filter(a -> "pocket-1".equals(a.getExternalAccountId())).findFirst().orElseThrow();

        assertThat(savedWallet.getParentAccountId()).isNull();
        assertThat(savedPocket.getParentAccountId()).isEqualTo(savedWallet.getId());
    }

    /** A money-box surfaces with type=SAVINGS and must be persisted as AccountType.SAVINGS. */
    @Test
    void sync_moneyBoxMapsToSavingsType() {
        FamilyMember member = member();
        when(categorizationService.loadContext(MEMBER_ID))
            .thenReturn(new CategorizationService.CategorizationContext(List.of(), Map.of()));

        RevolutAccountData moneyBox = new RevolutAccountData(
            "vault-1", "Voyage", AccountType.SAVINGS, null, bd("200.00"), "EUR", null, List.of());
        when(revolutPort.sync(PHONE, PASSCODE, MEMBER_ID)).thenReturn(List.of(moneyBox));

        when(accountRepository.findByExternalAccountIdAndMemberId("vault-1", MEMBER_ID)).thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("vault-1", MEMBER_ID)).thenReturn(false);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        stubSaveAssignsIncrementingIds(new AtomicLong(600));
        stubToResponseMirrorsAccount();

        service.sync(MEMBER_ID, PHONE, PASSCODE, false);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(AccountType.SAVINGS);
        assertThat(captor.getValue().getProvider()).isEqualTo("Revolut");
    }

    /** Dedup: a transaction whose externalId already exists on the account must not be re-saved. */
    @Test
    void sync_transactionDedup_existingExternalIdSkipped() {
        when(categorizationService.loadContext(MEMBER_ID))
            .thenReturn(new CategorizationService.CategorizationContext(List.of(), Map.of()));

        RevolutTxn existingTxn = new RevolutTxn("tx-old", LocalDate.of(2026, 6, 1), "Coffee", bd("-3.50"), "Cafe");
        RevolutTxn newTxn = new RevolutTxn("tx-new", LocalDate.of(2026, 6, 2), "Groceries", bd("-42.00"), "Carrefour");
        RevolutAccountData wallet = new RevolutAccountData(
            "wallet-2", "Revolut EUR", AccountType.CHECKING, null, bd("500.00"), "EUR", null,
            List.of(existingTxn, newTxn));
        when(revolutPort.sync(PHONE, PASSCODE, MEMBER_ID)).thenReturn(List.of(wallet));

        FamilyMember member = member();
        Account existingAccount = Account.builder()
            .id(900L).member(member).name("Revolut EUR").type(AccountType.CHECKING)
            .provider("Revolut").currency("EUR").currentBalance(bd("400.00"))
            .externalAccountId("wallet-2").isManual(false).color("#6366f1").build();
        when(accountRepository.findByExternalAccountIdAndMemberId("wallet-2", MEMBER_ID))
            .thenReturn(Optional.of(existingAccount));
        // No prior RevolutSession row -- applyPostSyncSessionState creates the "sidecar synced" marker.
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        stubToResponseMirrorsAccount();

        when(transactionRepository.existsByAccountIdAndExternalId(900L, "tx-old")).thenReturn(true);
        when(transactionRepository.existsByAccountIdAndExternalId(900L, "tx-new")).thenReturn(false);

        service.sync(MEMBER_ID, PHONE, PASSCODE, false);

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getExternalId()).isEqualTo("tx-new");
    }

    /**
     * IBAN-first matching: an existing Enable Banking account for the same current account
     * (matched by IBAN, different provider/uid) must be updated in place -- no duplicate row --
     * and its {@code provider} must stay untouched (whichever source got there first keeps
     * provenance), mirroring {@code SyncService.upsertAccount}.
     */
    @Test
    void sync_ibanMatch_updatesExistingEbAccount_noDuplicateProvenanceKept() {
        when(categorizationService.loadContext(MEMBER_ID))
            .thenReturn(new CategorizationService.CategorizationContext(List.of(), Map.of()));

        Account existingEbAccount = Account.builder()
            .id(77L).member(member()).name("Compte Courant").type(AccountType.CHECKING)
            .provider("Boursorama").currency("EUR").currentBalance(bd("1000.00"))
            .externalAccountId("eb-uid-old").iban(IBAN).isManual(false).color("#6366f1").build();

        RevolutAccountData walletData = new RevolutAccountData(
            "revolut-wallet-1", "Revolut EUR", AccountType.CHECKING, IBAN, bd("1500.00"), "EUR", null, List.of());
        when(revolutPort.sync(PHONE, PASSCODE, MEMBER_ID)).thenReturn(List.of(walletData));

        when(accountRepository.findByIbanAndMemberId(IBAN, MEMBER_ID)).thenReturn(Optional.of(existingEbAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        stubToResponseMirrorsAccount();
        // No prior RevolutSession row -- applyPostSyncSessionState creates the "sidecar synced" marker.
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));

        service.sync(MEMBER_ID, PHONE, PASSCODE, false);

        verify(accountRepository, never()).findByExternalAccountIdAndMemberId(anyString(), anyLong());

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        Account saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(77L);                          // same row -- no duplicate
        assertThat(saved.getExternalAccountId()).isEqualTo("revolut-wallet-1");
        assertThat(saved.getCurrentBalance()).isEqualByComparingTo("1500.00");
        assertThat(saved.getProvider()).isEqualTo("Boursorama");           // provenance untouched
    }

    // ─── Credential fallback (blank phone/passcode) ──────────────────────────────

    /** Blank phone/passcode falls back to the member's remembered, decrypted credentials. */
    @Test
    void sync_blankCredentials_fallsBackToStoredDecryptedCredentials() throws Exception {
        when(categorizationService.loadContext(MEMBER_ID))
            .thenReturn(new CategorizationService.CategorizationContext(List.of(), Map.of()));

        RevolutSession stored = RevolutSession.builder()
            .member(member())
            .credentialsEnc("enc-blob")
            .rememberCredentials(true)
            .build();
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(stored));
        // Computed as a separate statement: calling a Mockito-managed spy while a `when(...)` is
        // being set up for another mock confuses Mockito's stubbing state (UnfinishedStubbingException).
        String storedCredentialsJson = objectMapper.writeValueAsString(Map.of("phone", PHONE, "passcode", PASSCODE));
        when(encryption.decrypt("enc-blob")).thenReturn(storedCredentialsJson);

        RevolutAccountData wallet = new RevolutAccountData(
            "wallet-3", "Revolut EUR", AccountType.CHECKING, null, bd("10.00"), "EUR", null, List.of());
        when(revolutPort.sync(PHONE, PASSCODE, MEMBER_ID)).thenReturn(List.of(wallet));
        when(accountRepository.findByExternalAccountIdAndMemberId("wallet-3", MEMBER_ID)).thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("wallet-3", MEMBER_ID)).thenReturn(false);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        stubSaveAssignsIncrementingIds(new AtomicLong(700));
        stubToResponseMirrorsAccount();

        service.sync(MEMBER_ID, "", "", true);

        verify(revolutPort).sync(PHONE, PASSCODE, MEMBER_ID);
        // Falling back to stored credentials is NOT a voluntary reconnect -- no tombstone lift.
        verify(accountRepository, never()).restoreSoftDeletedRevolutAccounts(MEMBER_ID);
    }

    /** No stored credentials and blank input must fail clearly, not NPE. */
    @Test
    void sync_blankCredentials_noneStored_throwsClearError() {
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sync(MEMBER_ID, null, null, false))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("No saved Revolut credentials");
    }

    // ─── Remember opt-in ──────────────────────────────────────────────────────────

    /** remember=true persists the encrypted credentials + rememberCredentials=true + lastSyncedAt. */
    @Test
    void sync_rememberTrue_storesEncryptedCredentials() {
        when(categorizationService.loadContext(MEMBER_ID))
            .thenReturn(new CategorizationService.CategorizationContext(List.of(), Map.of()));
        when(revolutPort.sync(PHONE, PASSCODE, MEMBER_ID)).thenReturn(List.of());
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(encryption.encrypt(anyString())).thenReturn("encrypted-blob");

        service.sync(MEMBER_ID, PHONE, PASSCODE, true);

        ArgumentCaptor<RevolutSession> captor = ArgumentCaptor.forClass(RevolutSession.class);
        verify(sessionRepository).save(captor.capture());
        RevolutSession saved = captor.getValue();
        assertThat(saved.isRememberCredentials()).isTrue();
        assertThat(saved.getCredentialsEnc()).isEqualTo("encrypted-blob");
        assertThat(saved.getLastSyncedAt()).isNotNull();

        ArgumentCaptor<String> plainCaptor = ArgumentCaptor.forClass(String.class);
        verify(encryption).encrypt(plainCaptor.capture());
        assertThat(plainCaptor.getValue()).contains(PHONE).contains(PASSCODE);
    }

    /**
     * remember=false never persists credentials and clears any previously-remembered ones -- but
     * the row itself is kept (not deleted): it doubles as the "sidecar has synced this member"
     * marker that {@code RevolutPocketService} relies on to stand down its PSD2 heuristic
     * reconstruction, regardless of whether credentials were remembered.
     */
    @Test
    void sync_rememberFalse_clearsCredentialsButKeepsSessionMarkerRow() {
        when(categorizationService.loadContext(MEMBER_ID))
            .thenReturn(new CategorizationService.CategorizationContext(List.of(), Map.of()));
        when(revolutPort.sync(PHONE, PASSCODE, MEMBER_ID)).thenReturn(List.of());

        RevolutSession existing = RevolutSession.builder()
            .member(member()).credentialsEnc("old-enc").rememberCredentials(true).build();
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(existing));

        service.sync(MEMBER_ID, PHONE, PASSCODE, false);

        verify(sessionRepository, never()).delete(any(RevolutSession.class));
        ArgumentCaptor<RevolutSession> captor = ArgumentCaptor.forClass(RevolutSession.class);
        verify(sessionRepository).save(captor.capture());
        RevolutSession saved = captor.getValue();
        assertThat(saved.getCredentialsEnc()).isNull();
        assertThat(saved.isRememberCredentials()).isFalse();
        assertThat(saved.getLastSyncedAt()).isNotNull();
    }

    /**
     * Regression guard for the RevolutPocketService guard fix: even a member who NEVER remembers
     * credentials must get a RevolutSession row with lastSyncedAt set on their very first sync, or
     * RevolutPocketService cannot tell "the sidecar connector has synced this member" apart from
     * "no on-demand connector used at all" and would wrongly keep running its PSD2 heuristic
     * reconstruction alongside the real synced pockets.
     */
    @Test
    void sync_rememberFalse_firstEverSync_stillCreatesSessionMarkerRow() {
        when(categorizationService.loadContext(MEMBER_ID))
            .thenReturn(new CategorizationService.CategorizationContext(List.of(), Map.of()));
        when(revolutPort.sync(PHONE, PASSCODE, MEMBER_ID)).thenReturn(List.of());
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));

        service.sync(MEMBER_ID, PHONE, PASSCODE, false);

        ArgumentCaptor<RevolutSession> captor = ArgumentCaptor.forClass(RevolutSession.class);
        verify(sessionRepository).save(captor.capture());
        RevolutSession saved = captor.getValue();
        assertThat(saved.getLastSyncedAt()).isNotNull();
        assertThat(saved.isRememberCredentials()).isFalse();
        assertThat(saved.getCredentialsEnc()).isNull();
    }

    // ─── Voluntary reconnect ──────────────────────────────────────────────────────

    /**
     * Explicit phone+passcode is a voluntary reconnect (mirrors TradeRepublicSyncService's
     * completeAuth) -- tombstones must be lifted BEFORE the sync so upsertAccount updates rather
     * than silently skips previously soft-deleted accounts. See
     * docs/lessons/soft-delete-resurrection-guard-voluntary-reconnect.md.
     */
    @Test
    void sync_explicitCredentials_liftsSoftDeleteTombstonesBeforeSync() {
        when(categorizationService.loadContext(MEMBER_ID))
            .thenReturn(new CategorizationService.CategorizationContext(List.of(), Map.of()));
        when(revolutPort.sync(PHONE, PASSCODE, MEMBER_ID)).thenReturn(List.of());
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());
        // No prior RevolutSession row -- applyPostSyncSessionState creates the "sidecar synced" marker.
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));

        service.sync(MEMBER_ID, PHONE, PASSCODE, false);

        verify(accountRepository).restoreSoftDeletedRevolutAccounts(MEMBER_ID);
    }

    // ─── Scheduler entry point ───────────────────────────────────────────────────

    /**
     * Regression guard: resyncIfSessionActive must go through sync's blank-credentials fallback
     * (not pass the decrypted phone/passcode straight through as "explicit" input), or every
     * scheduled resync would be mistaken for a voluntary reconnect and silently lift soft-delete
     * tombstones -- reproducing the exact TR bug in
     * docs/lessons/soft-delete-resurrection-guard-voluntary-reconnect.md.
     */
    @Test
    void resyncIfSessionActive_rememberedSession_syncsWithoutLiftingTombstones() throws Exception {
        when(categorizationService.loadContext(MEMBER_ID))
            .thenReturn(new CategorizationService.CategorizationContext(List.of(), Map.of()));

        RevolutSession stored = RevolutSession.builder()
            .member(member())
            .credentialsEnc("enc-blob")
            .rememberCredentials(true)
            .build();
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(stored));
        String storedCredentialsJson = objectMapper.writeValueAsString(Map.of("phone", PHONE, "passcode", PASSCODE));
        when(encryption.decrypt("enc-blob")).thenReturn(storedCredentialsJson);
        when(revolutPort.sync(PHONE, PASSCODE, MEMBER_ID)).thenReturn(List.of());

        service.resyncIfSessionActive(MEMBER_ID);

        verify(revolutPort).sync(PHONE, PASSCODE, MEMBER_ID);
        verify(accountRepository, never()).restoreSoftDeletedRevolutAccounts(MEMBER_ID);
    }

    /** No remembered credentials -> no-op, never calls the sidecar. */
    @Test
    void resyncIfSessionActive_noRememberedCredentials_isNoop() {
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        service.resyncIfSessionActive(MEMBER_ID);

        verify(revolutPort, never()).sync(anyString(), anyString(), anyLong());
    }

    /** Never loops or retries -- a sidecar failure (e.g. a dead profile) is swallowed and logged. */
    @Test
    void resyncIfSessionActive_syncFails_swallowsException() throws Exception {
        RevolutSession stored = RevolutSession.builder()
            .member(member()).credentialsEnc("enc-blob").rememberCredentials(true).build();
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(stored));
        String storedCredentialsJson = objectMapper.writeValueAsString(Map.of("phone", PHONE, "passcode", PASSCODE));
        when(encryption.decrypt("enc-blob")).thenReturn(storedCredentialsJson);
        when(revolutPort.sync(PHONE, PASSCODE, MEMBER_ID)).thenThrow(new SyncException("APPROVAL_TIMEOUT"));

        assertThatCode(() -> service.resyncIfSessionActive(MEMBER_ID)).doesNotThrowAnyException();
    }
}
