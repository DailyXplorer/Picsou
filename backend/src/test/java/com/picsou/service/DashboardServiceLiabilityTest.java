package com.picsou.service;

import com.picsou.dto.DashboardResponse;
import com.picsou.model.*;
import com.picsou.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceLiabilityTest {

    @Mock AccountRepository accountRepository;
    @Mock GoalService goalService;
    @Mock GoalRepository goalRepository;
    @Mock PriceService priceService;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock HistoryService historyService;
    @Mock DebtRepository debtRepository;
    @Mock LoanAmortizationService loanAmortizationService;

    DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(
            accountRepository, goalService, goalRepository,
            priceService, holdingRepository, historyService,
            debtRepository, loanAmortizationService
        );
    }

    @Test
    void liability_with_debt_row_gets_monthlyPayment_and_percentPaid() {
        FamilyMember member = new FamilyMember();
        member.setId(1L);

        Account loan = new Account();
        loan.setId(10L);
        loan.setName("Mortgage");
        loan.setType(AccountType.LOAN);
        loan.setCurrentBalance(new BigDecimal("-80000"));
        loan.setCurrency("EUR");
        loan.setColor("#6366f1");

        Debt debt = new Debt();
        debt.setBorrowedAmount(new BigDecimal("100000"));
        debt.setMonthlyPayment(new BigDecimal("800"));
        debt.setInterestRate(new BigDecimal("0.015"));
        debt.setStartDate(LocalDate.of(2022, 1, 1));
        debt.setEndDate(LocalDate.of(2037, 1, 1));
        debt.setAccount(loan);

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(loan));
        when(holdingRepository.findByAccount_Id(10L)).thenReturn(List.of());
        when(priceService.toEur(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(debtRepository.findByAccountIdIn(List.of(10L))).thenReturn(List.of(debt));
        when(loanAmortizationService.resolveMonthlyPayment(debt)).thenReturn(new BigDecimal("800.00"));
        when(historyService.buildHistory(any(), any(Integer.class), any())).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        DashboardResponse result = dashboardService.getDashboard(1L, null);

        assertThat(result.liabilities()).hasSize(1);
        DashboardResponse.LiabilityEntry entry = result.liabilities().get(0);
        assertThat(entry.monthlyPayment()).isEqualByComparingTo("800.00");
        assertThat(entry.percentPaid()).isNotNull();
        // borrowedAmount=100000, remaining=80000 → 20% paid
        assertThat(entry.percentPaid()).isCloseTo(20.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(result.totalMonthlyPayment()).isEqualByComparingTo("800.00");
    }

    @Test
    void liability_without_debt_row_gets_null_fields() {
        FamilyMember member = new FamilyMember();
        member.setId(1L);

        Account loan = new Account();
        loan.setId(11L);
        loan.setName("Finary loan");
        loan.setType(AccountType.LOAN);
        loan.setCurrentBalance(new BigDecimal("-15000"));
        loan.setCurrency("EUR");
        loan.setColor("#f97316");

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(loan));
        when(holdingRepository.findByAccount_Id(11L)).thenReturn(List.of());
        when(priceService.toEur(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(debtRepository.findByAccountIdIn(List.of(11L))).thenReturn(List.of());
        when(historyService.buildHistory(any(), any(Integer.class), any())).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        DashboardResponse result = dashboardService.getDashboard(1L, null);

        assertThat(result.liabilities()).hasSize(1);
        DashboardResponse.LiabilityEntry entry = result.liabilities().get(0);
        assertThat(entry.monthlyPayment()).isNull();
        assertThat(entry.percentPaid()).isNull();
        assertThat(result.totalMonthlyPayment()).isNull();
    }

    @Test
    void liability_with_debt_missing_dates_returns_null_monthlyPayment() {
        Account loan = new Account();
        loan.setId(12L);
        loan.setName("Incomplete loan");
        loan.setType(AccountType.LOAN);
        loan.setCurrentBalance(new BigDecimal("-5000"));
        loan.setCurrency("EUR");
        loan.setColor("#aabbcc");

        Debt debt = new Debt();
        debt.setAccount(loan);
        debt.setBorrowedAmount(new BigDecimal("5000"));
        // intentionally no startDate / endDate / monthlyPayment

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(loan));
        when(holdingRepository.findByAccount_Id(12L)).thenReturn(List.of());
        when(priceService.toEur(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(debtRepository.findByAccountIdIn(List.of(12L))).thenReturn(List.of(debt));
        when(loanAmortizationService.resolveMonthlyPayment(debt)).thenReturn(null);
        when(historyService.buildHistory(any(), any(Integer.class), any())).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        DashboardResponse result = dashboardService.getDashboard(1L, null);

        assertThat(result.liabilities()).hasSize(1);
        assertThat(result.liabilities().get(0).monthlyPayment()).isNull();
        assertThat(result.totalMonthlyPayment()).isNull();
    }
}
