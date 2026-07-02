package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.FamilyMember;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RevolutSessionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RevolutPocketService}: regex detection, uuid extraction,
 * idempotent backfill, and placeholder naming.
 */
@ExtendWith(MockitoExtension.class)
class RevolutPocketServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock CategorizationService categorizationService;
    @Mock RevolutSessionRepository revolutSessionRepository;

    @InjectMocks RevolutPocketService service;

    private static final Long MEMBER_ID = 42L;
    private static final String POCKET_UUID = "3874abbf-c245-4d73-9df4-d4fcda89abfe";

    private Account wallet;
    private Category virementInterne;
    private FamilyMember member;

    @BeforeEach
    void setUp() {
        member = FamilyMember.builder().id(MEMBER_ID).build();
        wallet = Account.builder()
            .id(1L)
            .member(member)
            .name("Revolut")
            .type(AccountType.CHECKING)
            .provider("Revolut")
            .currency("EUR")
            .build();
        virementInterne = Category.builder()
            .id(99L).kind(CategoryKind.TRANSFER).slug("virement-interne").name("Virement interne")
            .build();
    }

    // ─── Regex detection ──────────────────────────────────────────────────────

    @Test
    void detect_realToEurMbPattern_returnsUuid() {
        Optional<String> result = service.detect("To EUR MB:3874abbf-c245-4d73-9df4-d4fcda89abfe");
        assertThat(result).isPresent().contains("3874abbf-c245-4d73-9df4-d4fcda89abfe");
    }

    @Test
    void detect_mixedCaseToEurMb_returnsUuid() {
        Optional<String> result = service.detect("To Eur Mb:3874abbf-c245-4d73-9df4-d4fcda89abfe");
        assertThat(result).isPresent().contains("3874abbf-c245-4d73-9df4-d4fcda89abfe");
    }

    @Test
    void detect_uuidOnlyEightChars_returnsUuid() {
        Optional<String> result = service.detect("To USD MB:abcd1234");
        assertThat(result).isPresent().contains("abcd1234");
    }

    @Test
    void detect_toEurAlone_isEmpty() {
        assertThat(service.detect("To EUR")).isEmpty();
    }

    @Test
    void detect_toRoboPortfolio_isEmpty() {
        assertThat(service.detect("To Robo portfolio")).isEmpty();
    }

    @Test
    void detect_toInvestmentPortfolio_isEmpty() {
        assertThat(service.detect("To investment portfolio by income sorter")).isEmpty();
    }

    @Test
    void detect_exchangedToEur_isEmpty() {
        assertThat(service.detect("Exchanged to EUR")).isEmpty();
    }

    @Test
    void detect_nullDescription_isEmpty() {
        assertThat(service.detect(null)).isEmpty();
    }

    @Test
    void detect_emptyDescription_isEmpty() {
        assertThat(service.detect("")).isEmpty();
    }

    // ─── Placeholder naming ───────────────────────────────────────────────────

    @Test
    void isPlaceholder_withPocketPrefix_isTrue() {
        assertThat(RevolutPocketService.isPlaceholder("Pocket ••89abfe")).isTrue();
    }

    @Test
    void isPlaceholder_withCustomName_isFalse() {
        assertThat(RevolutPocketService.isPlaceholder("Vacances")).isFalse();
    }

    @Test
    void isPlaceholder_null_isFalse() {
        assertThat(RevolutPocketService.isPlaceholder(null)).isFalse();
    }

    @Test
    void placeholderName_usesLast6OfUuid() {
        // The pocket name should end with the last 6 chars of the uuid
        when(categorizationService.categoriesBySlug(MEMBER_ID))
            .thenReturn(Map.of("virement-interne", virementInterne));
        when(accountRepository.findPocketByParentAndUuid(MEMBER_ID, wallet.getId(), POCKET_UUID))
            .thenReturn(Optional.empty());
        when(familyMemberRepository.getReferenceById(MEMBER_ID)).thenReturn(member);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(100L);
            return a;
        });
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString()))
            .thenReturn(false);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction walletTx = pocketTx(wallet, POCKET_UUID, "-100.00");
        service.processTransaction(walletTx, MEMBER_ID);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(1)).save(captor.capture());
        // The first saved Account is the new pocket
        Account savedPocket = captor.getAllValues().stream()
            .filter(a -> a.getParentAccountId() != null)
            .findFirst().orElseThrow();
        // Last 6 chars of "3874abbf-c245-4d73-9df4-d4fcda89abfe" = "89abfe"
        assertThat(savedPocket.getName()).isEqualTo("Pocket ••89abfe");
    }

    // ─── processTransaction (find-or-create, reclassify, mirror) ─────────────

    @Test
    void processTransaction_newPocket_createsSubAccountAndMirrorLeg() {
        when(categorizationService.categoriesBySlug(MEMBER_ID))
            .thenReturn(Map.of("virement-interne", virementInterne));
        when(accountRepository.findPocketByParentAndUuid(MEMBER_ID, wallet.getId(), POCKET_UUID))
            .thenReturn(Optional.empty());
        when(familyMemberRepository.getReferenceById(MEMBER_ID)).thenReturn(member);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            if (a.getId() == null) a.setId(200L);
            return a;
        });
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString()))
            .thenReturn(false);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction walletTx = pocketTx(wallet, POCKET_UUID, "-666.00");
        walletTx.setExternalId("ext-wallet-001");
        service.processTransaction(walletTx, MEMBER_ID);

        // Wallet tx must be reclassified to virement-interne
        assertThat(walletTx.getCategoryRef()).isEqualTo(virementInterne);

        // Mirror leg saved
        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(txCaptor.capture()); // wallet + mirror
        Transaction mirror = txCaptor.getAllValues().stream()
            .filter(t -> t.getExternalId() != null && t.getExternalId().startsWith("pocket-mirror:"))
            .findFirst().orElseThrow();
        assertThat(mirror.getAmount()).isEqualByComparingTo("666.00"); // negated = credit
        assertThat(mirror.getCategoryRef()).isEqualTo(virementInterne);
        assertThat(mirror.getExternalId()).isEqualTo("pocket-mirror:ext-wallet-001");
    }

    @Test
    void processTransaction_existingPocket_reusesPocketNoDuplicate() {
        Account existingPocket = Account.builder()
            .id(300L).member(member).name("Vacances").type(AccountType.CHECKING)
            .provider("Revolut").externalAccountId(POCKET_UUID).parentAccountId(wallet.getId())
            .build();
        when(categorizationService.categoriesBySlug(MEMBER_ID))
            .thenReturn(Map.of("virement-interne", virementInterne));
        when(accountRepository.findPocketByParentAndUuid(MEMBER_ID, wallet.getId(), POCKET_UUID))
            .thenReturn(Optional.of(existingPocket));
        when(transactionRepository.existsByAccountIdAndExternalId(eq(300L), anyString()))
            .thenReturn(false);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction walletTx = pocketTx(wallet, POCKET_UUID, "-108.00");
        walletTx.setExternalId("ext-wallet-002");
        service.processTransaction(walletTx, MEMBER_ID);

        // No new Account saved (pocket already existed)
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void processTransaction_softDeletedPocket_notResurrectedAndNoMirror() {
        when(categorizationService.categoriesBySlug(MEMBER_ID))
            .thenReturn(Map.of("virement-interne", virementInterne));
        when(accountRepository.findPocketByParentAndUuid(MEMBER_ID, wallet.getId(), POCKET_UUID))
            .thenReturn(Optional.empty());
        // The user deleted this pocket (soft-deleted, invisible to the JPQL finder above).
        when(accountRepository.existsSoftDeletedPocketByParentAndUuid(MEMBER_ID, wallet.getId(), POCKET_UUID))
            .thenReturn(true);

        Transaction walletTx = pocketTx(wallet, POCKET_UUID, "-50.00");
        walletTx.setExternalId("ext-wallet-deleted");
        service.processTransaction(walletTx, MEMBER_ID);

        // Wallet debit is still reclassified — the row remains a genuine internal transfer.
        assertThat(walletTx.getCategoryRef()).isEqualTo(virementInterne);
        // The deleted pocket is NOT recreated...
        verify(accountRepository, never()).save(any(Account.class));
        // ...and no mirror leg is attempted (only the wallet tx is saved).
        verify(transactionRepository, never()).existsByAccountIdAndExternalId(anyLong(), anyString());
        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }

    @Test
    void processTransaction_idempotent_mirrorNotDuplicated() {
        Account existingPocket = Account.builder()
            .id(400L).member(member).name("Vacances").type(AccountType.CHECKING)
            .provider("Revolut").externalAccountId(POCKET_UUID).parentAccountId(wallet.getId())
            .build();
        when(categorizationService.categoriesBySlug(MEMBER_ID))
            .thenReturn(Map.of("virement-interne", virementInterne));
        when(accountRepository.findPocketByParentAndUuid(MEMBER_ID, wallet.getId(), POCKET_UUID))
            .thenReturn(Optional.of(existingPocket));
        // Mirror leg already exists
        when(transactionRepository.existsByAccountIdAndExternalId(eq(400L), eq("pocket-mirror:ext-wallet-003")))
            .thenReturn(true);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction walletTx = pocketTx(wallet, POCKET_UUID, "-50.00");
        walletTx.setExternalId("ext-wallet-003");
        service.processTransaction(walletTx, MEMBER_ID);

        // Only the wallet tx reclassification save is called — no mirror save
        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getExternalId()).isEqualTo("ext-wallet-003"); // wallet, not mirror
    }

    @Test
    void processTransaction_nonPocketDescription_doesNothing() {
        Transaction walletTx = Transaction.builder()
            .id(9L).account(wallet).date(LocalDate.now())
            .description("AMAZON MARKETPLACE").amount(new BigDecimal("-29.99"))
            .externalId("ext-amazon-001").nativeCurrency("EUR").build();

        service.processTransaction(walletTx, MEMBER_ID);

        verify(accountRepository, never()).findPocketByParentAndUuid(anyLong(), anyLong(), anyString());
        verify(transactionRepository, never()).save(any());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static Transaction pocketTx(Account account, String uuid, String amount) {
        return Transaction.builder()
            .id(System.nanoTime())
            .account(account)
            .date(LocalDate.of(2026, 6, 1))
            .description("To EUR MB:" + uuid)
            .amount(new BigDecimal(amount))
            .nativeCurrency("EUR")
            .isManual(false)
            .build();
    }
}
