package com.picsou.mcp.tools;

import com.picsou.dto.AccountRequest;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.HoldingResponse;
import com.picsou.dto.SnapshotRequest;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.FamilyMember;
import com.picsou.service.AccountService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every tool must resolve {@link UserContext#currentMemberId()} (or {@code currentMember()}) and
 * delegate to the already member-scoped {@link AccountService} — never reaching across members.
 * These tests pin that delegation; member isolation itself is enforced (and tested) in the service.
 */
@ExtendWith(MockitoExtension.class)
class AccountToolsTest {

    private static final long MID = 7L;

    @Mock AccountService accountService;
    @Mock UserContext userContext;
    @InjectMocks AccountTools tools;

    @Test
    void listAccounts_delegatesScopedToCurrentMember() {
        AccountResponse r = mock(AccountResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.findAll(MID)).thenReturn(List.of(r));

        assertThat(tools.listAccounts()).containsExactly(r);
    }

    @Test
    void getAccount_delegatesScopedToCurrentMember() {
        AccountResponse r = mock(AccountResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.findById(5L, MID)).thenReturn(r);

        assertThat(tools.getAccount(5L)).isSameAs(r);
    }

    @Test
    void getAccountHoldings_delegatesScopedToCurrentMember() {
        HoldingResponse h = mock(HoldingResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.getHoldings(5L, MID)).thenReturn(List.of(h));

        assertThat(tools.getAccountHoldings(5L)).containsExactly(h);
    }

    @Test
    void getAccountBalanceHistory_delegatesScopedToCurrentMember() {
        BalanceSnapshot s = mock(BalanceSnapshot.class);
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 1);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.getHistory(5L, MID, from, to)).thenReturn(List.of(s));

        assertThat(tools.getAccountBalanceHistory(5L, from, to)).containsExactly(s);
    }

    @Test
    void createManualAccount_forcesManualFlagAndDelegatesWithCurrentMember() {
        FamilyMember member = FamilyMember.builder().id(MID).build();
        AccountResponse created = mock(AccountResponse.class);
        when(userContext.currentMember()).thenReturn(member);
        when(accountService.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(member)))
            .thenReturn(created);

        AccountResponse out = tools.createManualAccount(
            "Livret", AccountType.SAVINGS, "EUR", new BigDecimal("100.00"), "#abcdef", null);

        assertThat(out).isSameAs(created);
        ArgumentCaptor<AccountRequest> captor = ArgumentCaptor.forClass(AccountRequest.class);
        verify(accountService).create(captor.capture(), org.mockito.ArgumentMatchers.eq(member));
        AccountRequest req = captor.getValue();
        assertThat(req.isManual()).isTrue();              // MCP can only create *manual* accounts
        assertThat(req.name()).isEqualTo("Livret");
        assertThat(req.type()).isEqualTo(AccountType.SAVINGS);
        assertThat(req.currency()).isEqualTo("EUR");
        assertThat(req.currentBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void updateAccount_delegatesScopedToCurrentMember() {
        AccountResponse updated = mock(AccountResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.update(org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(MID))).thenReturn(updated);

        AccountResponse out = tools.updateAccount(
            5L, "Renamed", AccountType.CHECKING, "EUR", null, null, null);

        assertThat(out).isSameAs(updated);
        verify(accountService).update(org.mockito.ArgumentMatchers.eq(5L),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(MID));
    }

    @Test
    void deleteAccount_delegatesScopedToCurrentMember() {
        when(userContext.currentMemberId()).thenReturn(MID);

        tools.deleteAccount(5L);

        verify(accountService).delete(5L, MID);
    }

    @Test
    void addBalanceSnapshot_delegatesScopedToCurrentMember() {
        BalanceSnapshot saved = mock(BalanceSnapshot.class);
        LocalDate date = LocalDate.of(2026, 6, 4);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.addManualSnapshot(org.mockito.ArgumentMatchers.eq(5L),
            org.mockito.ArgumentMatchers.eq(MID), org.mockito.ArgumentMatchers.any(SnapshotRequest.class)))
            .thenReturn(saved);

        BalanceSnapshot out = tools.addBalanceSnapshot(5L, new BigDecimal("250.50"), date);

        assertThat(out).isSameAs(saved);
        ArgumentCaptor<SnapshotRequest> captor = ArgumentCaptor.forClass(SnapshotRequest.class);
        verify(accountService).addManualSnapshot(org.mockito.ArgumentMatchers.eq(5L),
            org.mockito.ArgumentMatchers.eq(MID), captor.capture());
        assertThat(captor.getValue().balance()).isEqualByComparingTo("250.50");
        assertThat(captor.getValue().date()).isEqualTo(date);
    }

    @Test
    void upsertHolding_upsertsThenReturnsHoldingsDto_neverTheEntity() {
        HoldingResponse h = mock(HoldingResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.getHoldings(5L, MID)).thenReturn(List.of(h));

        List<HoldingResponse> out = tools.upsertHolding(5L, "AAPL", "Apple", new BigDecimal("3"), new BigDecimal("180"));

        assertThat(out).containsExactly(h);
        verify(accountService).upsertHolding(5L, MID, "AAPL", "Apple", new BigDecimal("3"), new BigDecimal("180"));
        verify(accountService).getHoldings(5L, MID);
    }

    @Test
    void deleteHolding_delegatesScopedToCurrentMember() {
        when(userContext.currentMemberId()).thenReturn(MID);

        tools.deleteHolding(5L, "AAPL");

        verify(accountService).deleteHolding(5L, MID, "AAPL");
    }
}
