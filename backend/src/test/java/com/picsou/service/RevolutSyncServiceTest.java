package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Unit tests for {@link RevolutSyncService}: pocket → account mapping with parentAccountId
 * resolution, money-box → SAVINGS, transaction dedup, and IBAN-first matching against an
 * existing Enable Banking account (no duplicate, provenance untouched).
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
    @Mock TransactionTemplate txTemplate;

    @InjectMocks RevolutSyncService service;

    private static final Long MEMBER_ID = 21L;
    private static final String IBAN = "FR7630006000011234567890189";

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private FamilyMember member() {
        return FamilyMember.builder().id(MEMBER_ID).build();
    }

    private void stubActiveSession(FamilyMember member) {
        RevolutSession stored = RevolutSession.builder()
            .member(member)
            .storageState("enc-state")
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
        when(sessionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(stored));
        when(encryption.decrypt("enc-state")).thenReturn("plain-state");
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

    /**
     * The sidecar returns a pocket before its parent wallet in the raw list; the service must
     * still upsert the wallet first (sorted by parentExternalId presence) so the pocket's saved
     * Account carries parentAccountId = the wallet's freshly-assigned Picsou account id.
     */
    @Test
    void sync_pocketGetsParentAccountIdFromWallet() {
        FamilyMember member = member();
        stubActiveSession(member);
        when(categorizationService.loadContext(MEMBER_ID))
            .thenReturn(new CategorizationService.CategorizationContext(List.of(), Map.of()));

        RevolutAccountData wallet = new RevolutAccountData(
            "wallet-1", "Revolut EUR", AccountType.CHECKING, IBAN, bd("100.00"), "EUR", null, List.of());
        RevolutAccountData pocket = new RevolutAccountData(
            "pocket-1", "Pocket ••abc123", AccountType.CHECKING, null, bd("50.00"), "EUR", "wallet-1", List.of());
        // Sidecar order is not guaranteed -- pocket listed before its wallet on purpose.
        when(revolutPort.fetchAccounts("plain-state")).thenReturn(List.of(pocket, wallet));

        when(accountRepository.findByIbanAndMemberId(IBAN, MEMBER_ID)).thenReturn(Optional.empty());
        when(accountRepository.findByExternalAccountIdAndMemberId("wallet-1", MEMBER_ID)).thenReturn(Optional.empty());
        when(accountRepository.findByExternalAccountIdAndMemberId("pocket-1", MEMBER_ID)).thenReturn(Optional.empty());
        lenient().when(accountRepository.existsSoftDeletedByIbanAndMemberId(IBAN, MEMBER_ID)).thenReturn(false);
        lenient().when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(anyString(), eq(MEMBER_ID)))
            .thenReturn(false);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        stubSaveAssignsIncrementingIds(new AtomicLong(500));
        stubToResponseMirrorsAccount();

        service.sync(MEMBER_ID);

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
        stubActiveSession(member);
        when(categorizationService.loadContext(MEMBER_ID))
            .thenReturn(new CategorizationService.CategorizationContext(List.of(), Map.of()));

        RevolutAccountData moneyBox = new RevolutAccountData(
            "vault-1", "Voyage", AccountType.SAVINGS, null, bd("200.00"), "EUR", null, List.of());
        when(revolutPort.fetchAccounts("plain-state")).thenReturn(List.of(moneyBox));

        when(accountRepository.findByExternalAccountIdAndMemberId("vault-1", MEMBER_ID)).thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("vault-1", MEMBER_ID)).thenReturn(false);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        stubSaveAssignsIncrementingIds(new AtomicLong(600));
        stubToResponseMirrorsAccount();

        service.sync(MEMBER_ID);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(AccountType.SAVINGS);
        assertThat(captor.getValue().getProvider()).isEqualTo("Revolut");
    }

    /** Dedup: a transaction whose externalId already exists on the account must not be re-saved. */
    @Test
    void sync_transactionDedup_existingExternalIdSkipped() {
        FamilyMember member = member();
        stubActiveSession(member);
        when(categorizationService.loadContext(MEMBER_ID))
            .thenReturn(new CategorizationService.CategorizationContext(List.of(), Map.of()));

        RevolutTxn existingTxn = new RevolutTxn("tx-old", LocalDate.of(2026, 6, 1), "Coffee", bd("-3.50"), "Cafe");
        RevolutTxn newTxn = new RevolutTxn("tx-new", LocalDate.of(2026, 6, 2), "Groceries", bd("-42.00"), "Carrefour");
        RevolutAccountData wallet = new RevolutAccountData(
            "wallet-2", "Revolut EUR", AccountType.CHECKING, null, bd("500.00"), "EUR", null,
            List.of(existingTxn, newTxn));
        when(revolutPort.fetchAccounts("plain-state")).thenReturn(List.of(wallet));

        Account existingAccount = Account.builder()
            .id(900L).member(member).name("Revolut EUR").type(AccountType.CHECKING)
            .provider("Revolut").currency("EUR").currentBalance(bd("400.00"))
            .externalAccountId("wallet-2").isManual(false).color("#6366f1").build();
        when(accountRepository.findByExternalAccountIdAndMemberId("wallet-2", MEMBER_ID))
            .thenReturn(Optional.of(existingAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        stubToResponseMirrorsAccount();

        when(transactionRepository.existsByAccountIdAndExternalId(900L, "tx-old")).thenReturn(true);
        when(transactionRepository.existsByAccountIdAndExternalId(900L, "tx-new")).thenReturn(false);

        service.sync(MEMBER_ID);

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
        FamilyMember member = member();
        stubActiveSession(member);
        when(categorizationService.loadContext(MEMBER_ID))
            .thenReturn(new CategorizationService.CategorizationContext(List.of(), Map.of()));

        Account existingEbAccount = Account.builder()
            .id(77L).member(member).name("Compte Courant").type(AccountType.CHECKING)
            .provider("Boursorama").currency("EUR").currentBalance(bd("1000.00"))
            .externalAccountId("eb-uid-old").iban(IBAN).isManual(false).color("#6366f1").build();

        RevolutAccountData walletData = new RevolutAccountData(
            "revolut-wallet-1", "Revolut EUR", AccountType.CHECKING, IBAN, bd("1500.00"), "EUR", null, List.of());
        when(revolutPort.fetchAccounts("plain-state")).thenReturn(List.of(walletData));

        when(accountRepository.findByIbanAndMemberId(IBAN, MEMBER_ID)).thenReturn(Optional.of(existingEbAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        stubToResponseMirrorsAccount();

        service.sync(MEMBER_ID);

        verify(accountRepository, never()).findByExternalAccountIdAndMemberId(anyString(), anyLong());

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        Account saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(77L);                          // same row -- no duplicate
        assertThat(saved.getExternalAccountId()).isEqualTo("revolut-wallet-1");
        assertThat(saved.getCurrentBalance()).isEqualByComparingTo("1500.00");
        assertThat(saved.getProvider()).isEqualTo("Boursorama");           // provenance untouched
    }
}
