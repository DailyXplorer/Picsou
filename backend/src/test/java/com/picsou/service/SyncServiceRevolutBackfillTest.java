package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.Requisition;
import com.picsou.model.RequisitionStatus;
import com.picsou.port.BankConnectorPort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.budget.RecurringDetectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that SyncService triggers {@link RevolutPocketService#backfillForMember} after each
 * member sync so that historical "To … MB" rows are reconstructed, not just the 90-day window.
 */
@ExtendWith(MockitoExtension.class)
class SyncServiceRevolutBackfillTest {

    @Mock BankConnectorPort bankConnector;
    @Mock AccountRepository accountRepository;
    @Mock RequisitionRepository requisitionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock TransactionRepository transactionRepository;
    @Mock CategorizationService categorizationService;
    @Mock RecurringDetectionService recurringDetectionService;
    @Mock RevolutPocketService revolutPocketService;

    @InjectMocks SyncService syncService;

    private static final Long MEMBER_ID = 11L;

    @Test
    void resyncAll_withLinkedRequisition_callsBackfillForMember() {
        FamilyMember member = FamilyMember.builder().id(MEMBER_ID).build();
        Requisition req = Requisition.builder()
            .id(1L).member(member).requisitionId("sess-001")
            .institutionName("Revolut").status(RequisitionStatus.LINKED)
            .build();

        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(
                RequisitionStatus.LINKED, MEMBER_ID))
            .thenReturn(List.of(req));

        Account wallet = Account.builder()
            .id(10L).member(member).name("Revolut").type(AccountType.CHECKING)
            .provider("Revolut").externalAccountId("ext-rev").currency("EUR")
            .currentBalance(BigDecimal.valueOf(1000)).build();

        BankConnectorPort.AccountData accountData = new BankConnectorPort.AccountData(
            "ext-rev", "Revolut", null, "EUR", BigDecimal.valueOf(1000));
        when(bankConnector.fetchBalances("sess-001")).thenReturn(List.of(accountData));
        when(accountRepository.findByExternalAccountIdAndMemberId("ext-rev", MEMBER_ID))
            .thenReturn(Optional.of(wallet));
        // save() must return the account so upsertAccount() doesn't NPE mid-way
        when(accountRepository.save(any(Account.class))).thenReturn(wallet);
        // toResponse() must return non-null: Optional.of(null) would throw NPE caught by resyncAll's
        // try/catch, silently preventing runPocketBackfill from being reached.
        AccountResponse fakeResponse = new AccountResponse(
            10L, "Revolut", AccountType.CHECKING, "Revolut", "EUR",
            BigDecimal.valueOf(1000), BigDecimal.valueOf(1000),
            null, false, "#6366f1", null, null, null, null, null);
        when(accountService.toResponse(any(Account.class))).thenReturn(fakeResponse);
        when(bankConnector.fetchTransactions(anyString(), anyString(), any()))
            .thenReturn(List.of());
        when(categorizationService.loadContext(MEMBER_ID))
            .thenReturn(new CategorizationService.CategorizationContext(List.of(), java.util.Map.of()));

        syncService.resyncAll(MEMBER_ID);

        // Backfill must be called once for the member after transactions are ingested
        verify(revolutPocketService).backfillForMember(MEMBER_ID);
    }

    @Test
    void backfillForMember_noRevolutWallets_isNoop() {
        // RevolutPocketService.backfillForMember is already a no-op when the wallet list is empty.
        // This test documents the contract via the service directly.
        when(accountRepository.findRevolutWalletsByMemberId(MEMBER_ID)).thenReturn(List.of());

        // Should complete instantly with no further interactions
        new RevolutPocketService(
            accountRepository, transactionRepository, familyMemberRepository, categorizationService
        ).backfillForMember(MEMBER_ID);

        verify(transactionRepository, never()).findByAccountIdOrderByDateAsc(anyLong());
    }
}
