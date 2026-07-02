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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that verify the SyncService pocket integration path:
 * a "To … MB:<uuid>" row yields a reclassified wallet leg, a pocket sub-account with
 * the correct parent_account_id, and one mirror credit leg.
 *
 * <p>These tests exercise {@link RevolutPocketService} directly (not via SyncService
 * internals) to cover the contact surface that SyncService calls after ingest.
 */
@ExtendWith(MockitoExtension.class)
class SyncServicePocketTest {

    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock CategorizationService categorizationService;
    @Mock RevolutSessionRepository revolutSessionRepository;

    @InjectMocks RevolutPocketService pocketService;

    private static final Long MEMBER_ID = 7L;
    private static final String UUID = "76fe0dd0-c245-4d73-9df4-d4fcda89abfe";

    private FamilyMember member;
    private Account wallet;
    private Category virementInterne;

    @BeforeEach
    void setUp() {
        member = FamilyMember.builder().id(MEMBER_ID).build();
        wallet = Account.builder()
            .id(10L).member(member).name("Revolut").type(AccountType.CHECKING)
            .provider("Revolut").currency("EUR").build();
        virementInterne = Category.builder()
            .id(55L).slug("virement-interne").kind(CategoryKind.TRANSFER).name("Virement interne")
            .build();
    }

    @Test
    void toMbRow_producesReclassifiedWalletLeg_pocket_andMirrorCredit() {
        when(categorizationService.categoriesBySlug(MEMBER_ID))
            .thenReturn(Map.of("virement-interne", virementInterne));
        when(accountRepository.findPocketByParentAndUuid(MEMBER_ID, wallet.getId(), UUID))
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

        Transaction walletTx = Transaction.builder()
            .id(1L).account(wallet)
            .date(LocalDate.of(2026, 5, 10))
            .description("To EUR MB:" + UUID)
            .amount(new BigDecimal("-666.00"))
            .externalId("eb-wallet-001")
            .nativeCurrency("EUR")
            .build();

        pocketService.processTransaction(walletTx, MEMBER_ID);

        // 1. Wallet leg reclassified to virement-interne
        assertThat(walletTx.getCategoryRef()).isNotNull();
        assertThat(walletTx.getCategoryRef().getSlug()).isEqualTo("virement-interne");
        assertThat(walletTx.getCategoryRef().getKind()).isEqualTo(CategoryKind.TRANSFER);

        // 2. Pocket sub-account created with correct parent_account_id
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        Account pocket = accountCaptor.getValue();
        assertThat(pocket.getParentAccountId()).isEqualTo(wallet.getId());
        assertThat(pocket.getType()).isEqualTo(AccountType.CHECKING);
        assertThat(pocket.getProvider()).isEqualTo("Revolut");
        assertThat(pocket.getExternalAccountId()).isEqualTo(UUID);

        // 3. Mirror credit leg created in pocket
        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, org.mockito.Mockito.times(2)).save(txCaptor.capture());
        List<Transaction> saved = txCaptor.getAllValues();
        Transaction mirror = saved.stream()
            .filter(t -> t.getExternalId() != null && t.getExternalId().startsWith("pocket-mirror:"))
            .findFirst().orElseThrow();
        assertThat(mirror.getAmount()).isEqualByComparingTo("666.00"); // credit (+)
        assertThat(mirror.getCategoryRef().getKind()).isEqualTo(CategoryKind.TRANSFER);
        assertThat(mirror.getExternalId()).isEqualTo("pocket-mirror:eb-wallet-001");
    }

    @Test
    void toMbRow_backfill_allWalletTxProcessed() {
        // Backfill scans all wallet accounts and processes each matching tx.
        List<Transaction> txs = List.of(
            walletTx("-100.00", "ext-1"),
            walletTx("-200.00", "ext-2")
        );
        when(accountRepository.findRevolutWalletsByMemberId(MEMBER_ID))
            .thenReturn(List.of(wallet));
        when(transactionRepository.findByAccountIdOrderByDateAsc(wallet.getId()))
            .thenReturn(txs);
        when(categorizationService.categoriesBySlug(MEMBER_ID))
            .thenReturn(Map.of("virement-interne", virementInterne));
        when(accountRepository.findPocketByParentAndUuid(eq(MEMBER_ID), eq(wallet.getId()), eq(UUID)))
            .thenReturn(Optional.empty());
        when(familyMemberRepository.getReferenceById(MEMBER_ID)).thenReturn(member);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            if (a.getId() == null) a.setId(300L);
            return a;
        });
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString()))
            .thenReturn(false);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        pocketService.backfillForMember(MEMBER_ID);

        // Both txs reclassified
        assertThat(txs.get(0).getCategoryRef().getSlug()).isEqualTo("virement-interne");
        assertThat(txs.get(1).getCategoryRef().getSlug()).isEqualTo("virement-interne");
    }

    private Transaction walletTx(String amount, String extId) {
        return Transaction.builder()
            .id(System.nanoTime())
            .account(wallet)
            .date(LocalDate.of(2026, 5, 1))
            .description("To EUR MB:" + UUID)
            .amount(new BigDecimal(amount))
            .externalId(extId)
            .nativeCurrency("EUR")
            .build();
    }
}
