package com.picsou.mcp.tools;

import com.picsou.dto.TransactionRequest;
import com.picsou.dto.TransactionResponse;
import com.picsou.model.TransactionType;
import com.picsou.service.AccountService;
import com.picsou.service.ManualTransactionService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionToolsTest {

    private static final long MID = 7L;

    @Mock AccountService accountService;
    @Mock ManualTransactionService manualTransactionService;
    @Mock UserContext userContext;
    @InjectMocks TransactionTools tools;

    @Test
    void listAccountTransactions_delegatesScopedToCurrentMember() {
        TransactionResponse t = mock(TransactionResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accountService.getTransactions(5L, MID)).thenReturn(List.of(t));

        assertThat(tools.listAccountTransactions(5L)).containsExactly(t);
    }

    @Test
    void addTransaction_buildsRequestAndDelegatesScopedToCurrentMember() {
        TransactionResponse created = mock(TransactionResponse.class);
        LocalDate date = LocalDate.of(2026, 6, 4);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(manualTransactionService.addTransaction(eq(5L), eq(MID), any(TransactionRequest.class))).thenReturn(created);

        TransactionResponse out = tools.addTransaction(
            5L, date, "Coffee", new BigDecimal("-3.50"), TransactionType.WITHDRAWAL, null, null, null, null, "EUR");

        assertThat(out).isSameAs(created);
        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(manualTransactionService).addTransaction(eq(5L), eq(MID), captor.capture());
        TransactionRequest req = captor.getValue();
        assertThat(req.date()).isEqualTo(date);
        assertThat(req.description()).isEqualTo("Coffee");
        assertThat(req.amount()).isEqualByComparingTo("-3.50");
        assertThat(req.txType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(req.currency()).isEqualTo("EUR");
    }

    @Test
    void updateTransaction_delegatesScopedToCurrentMember() {
        TransactionResponse updated = mock(TransactionResponse.class);
        LocalDate date = LocalDate.of(2026, 6, 4);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(manualTransactionService.updateTransaction(eq(5L), eq(9L), eq(MID), any(TransactionRequest.class)))
            .thenReturn(updated);

        TransactionResponse out = tools.updateTransaction(
            5L, 9L, date, "Lunch", new BigDecimal("-12.00"), TransactionType.WITHDRAWAL, null, null, null, null, "EUR");

        assertThat(out).isSameAs(updated);
        verify(manualTransactionService).updateTransaction(eq(5L), eq(9L), eq(MID), any(TransactionRequest.class));
    }

    @Test
    void deleteTransaction_delegatesScopedToCurrentMember() {
        when(userContext.currentMemberId()).thenReturn(MID);

        tools.deleteTransaction(5L, 9L);

        verify(manualTransactionService).deleteTransaction(5L, 9L, MID);
    }
}
