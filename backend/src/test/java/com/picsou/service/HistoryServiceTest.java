package com.picsou.service;

import com.picsou.dto.DashboardResponse.NetWorthPoint;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.PriceSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoryServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock PriceService priceService;
    @Mock PriceSnapshotRepository priceSnapshotRepository;
    @Mock AccountService accountService;

    @InjectMocks HistoryService historyService;

    private static final long MEMBER_ID = 99L;
    private static final FamilyMember MEMBER = FamilyMember.builder().id(MEMBER_ID).build();

    private static Account brokerage(long id, String name) {
        return Account.builder()
            .id(id)
            .name(name)
            .type(AccountType.COMPTE_TITRES)
            .currency("EUR")
            .currentBalance(new BigDecimal("0"))
            .color("#6366f1")
            .member(MEMBER)
            .build();
    }

    @Test
    void buildHistory_invested_readsSnapshotPerDate() {
        LocalDate today = LocalDate.now();
        Account account = brokerage(1L, "CT");

        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(account));
        when(snapshotRepository.findForwardFillDataByAccountIds(any(LocalDate.class), eq(List.of(1L))))
            .thenReturn(List.of(
                new Object[]{1L, today.minusDays(10), new BigDecimal("5000"), new BigDecimal("4500")},
                new Object[]{1L, today.minusDays(5),  new BigDecimal("5500"), new BigDecimal("5000")},
                new Object[]{1L, today.minusDays(1),  new BigDecimal("6200"), new BigDecimal("5400")}
            ));
        when(accountService.liveBalanceEur(account)).thenReturn(new BigDecimal("6200"));
        when(accountService.calculateInvestedAmount(account)).thenReturn(new BigDecimal("5400"));

        List<NetWorthPoint> result = historyService.buildHistory(List.of(1L), 1, false, MEMBER_ID);

        // Three historical points + today's appended live point.
        assertThat(result).hasSize(4);

        assertThat(result.get(0).date()).isEqualTo(today.minusDays(10));
        assertThat(result.get(0).invested()).isEqualByComparingTo("4500");

        assertThat(result.get(1).date()).isEqualTo(today.minusDays(5));
        assertThat(result.get(1).invested()).isEqualByComparingTo("5000");

        assertThat(result.get(2).date()).isEqualTo(today.minusDays(1));
        assertThat(result.get(2).invested()).isEqualByComparingTo("5400");

        // Distinct values — proves we read row[3] per row, not a single constant.
    }

    @Test
    void buildHistory_todayPoint_usesLiveCalculation_notSnapshot() {
        LocalDate today = LocalDate.now();
        Account account = brokerage(1L, "CT");

        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(account));
        when(snapshotRepository.findForwardFillDataByAccountIds(any(LocalDate.class), eq(List.of(1L))))
            .thenReturn(List.<Object[]>of(
                // Stale snapshot for today: balance and invested both behind reality.
                new Object[]{1L, today, new BigDecimal("5000"), new BigDecimal("4500")}
            ));
        when(accountService.liveBalanceEur(account)).thenReturn(new BigDecimal("5100"));
        when(accountService.calculateInvestedAmount(account)).thenReturn(new BigDecimal("4800"));

        List<NetWorthPoint> result = historyService.buildHistory(List.of(1L), 1, false, MEMBER_ID);

        NetWorthPoint todayPoint = result.get(result.size() - 1);
        assertThat(todayPoint.date()).isEqualTo(today);
        assertThat(todayPoint.total()).isEqualByComparingTo("5100");
        assertThat(todayPoint.invested()).isEqualByComparingTo("4800");
    }

    @Test
    void buildHistory_loan_contributesZeroToInvested_negativeToTotal() {
        LocalDate today = LocalDate.now();
        LocalDate date = today.minusDays(2);

        Account loan = Account.builder()
            .id(1L).name("Loan").type(AccountType.LOAN).currency("EUR")
            .currentBalance(new BigDecimal("10000")).color("#ef4444").member(MEMBER).build();
        Account checking = Account.builder()
            .id(2L).name("Checking").type(AccountType.CHECKING).currency("EUR")
            .currentBalance(new BigDecimal("2000")).color("#3b82f6").member(MEMBER).build();

        when(accountRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(loan, checking));
        when(snapshotRepository.findForwardFillDataByAccountIds(any(LocalDate.class), eq(List.of(1L, 2L))))
            .thenReturn(List.of(
                new Object[]{1L, date, new BigDecimal("10000"), new BigDecimal("10000")},
                new Object[]{2L, date, new BigDecimal("2000"),  new BigDecimal("2000")}
            ));
        lenient().when(accountService.liveBalanceEur(loan)).thenReturn(new BigDecimal("10000"));
        lenient().when(accountService.liveBalanceEur(checking)).thenReturn(new BigDecimal("2000"));
        lenient().when(accountService.calculateInvestedAmount(loan)).thenReturn(new BigDecimal("10000"));
        lenient().when(accountService.calculateInvestedAmount(checking)).thenReturn(new BigDecimal("2000"));

        List<NetWorthPoint> result = historyService.buildHistory(List.of(1L, 2L), 1, true, MEMBER_ID);

        NetWorthPoint point = result.stream()
            .filter(p -> p.date().equals(date))
            .findFirst()
            .orElseThrow();

        // total: checking +2000, loan -10000 → -8000.
        assertThat(point.total()).isEqualByComparingTo("-8000");
        // invested: loan contributes 0, checking contributes 2000.
        assertThat(point.invested()).isEqualByComparingTo("2000");
        // Per-account split: loan invested is ZERO regardless of its snapshot column.
        assertThat(point.accounts().get(1L).invested()).isEqualByComparingTo("0");
        assertThat(point.accounts().get(2L).invested()).isEqualByComparingTo("2000");
    }

    @Test
    void buildHistory_forwardFill_carriesLastInvestedAcrossGap() {
        LocalDate today = LocalDate.now();
        Account brokerage = brokerage(1L, "CT");
        Account checking = Account.builder()
            .id(2L).name("Checking").type(AccountType.CHECKING).currency("EUR")
            .currentBalance(new BigDecimal("1000")).color("#3b82f6").member(MEMBER).build();

        when(accountRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(brokerage, checking));
        when(snapshotRepository.findForwardFillDataByAccountIds(any(LocalDate.class), eq(List.of(1L, 2L))))
            .thenReturn(List.of(
                // Brokerage: snapshots at D-7 and D-3, gap between.
                new Object[]{1L, today.minusDays(7), new BigDecimal("3000"), new BigDecimal("3000")},
                new Object[]{1L, today.minusDays(3), new BigDecimal("3200"), new BigDecimal("3200")},
                // Checking: snapshot at D-5 (inside the brokerage gap) — injects this date into ffData.dates.
                new Object[]{2L, today.minusDays(5), new BigDecimal("1000"), new BigDecimal("1000")}
            ));
        lenient().when(accountService.liveBalanceEur(brokerage)).thenReturn(new BigDecimal("3200"));
        lenient().when(accountService.liveBalanceEur(checking)).thenReturn(new BigDecimal("1000"));
        lenient().when(accountService.calculateInvestedAmount(brokerage)).thenReturn(new BigDecimal("3200"));
        lenient().when(accountService.calculateInvestedAmount(checking)).thenReturn(new BigDecimal("1000"));

        List<NetWorthPoint> result = historyService.buildHistory(List.of(1L, 2L), 1, false, MEMBER_ID);

        // At D-5: brokerage forward-fills from D-7 (invested=3000), checking has its own row (1000).
        NetWorthPoint atD5 = result.stream()
            .filter(p -> p.date().equals(today.minusDays(5)))
            .findFirst()
            .orElseThrow();
        assertThat(atD5.invested()).isEqualByComparingTo("4000");
    }

    @Test
    void buildHistory_split_perAccountInvested_matchesAggregate() {
        LocalDate today = LocalDate.now();
        LocalDate date = today.minusDays(2);

        Account acc1 = brokerage(1L, "CT1");
        Account acc2 = brokerage(2L, "CT2");

        when(accountRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(acc1, acc2));
        when(snapshotRepository.findForwardFillDataByAccountIds(any(LocalDate.class), eq(List.of(1L, 2L))))
            .thenReturn(List.of(
                new Object[]{1L, date, new BigDecimal("1200"), new BigDecimal("1000")},
                new Object[]{2L, date, new BigDecimal("2800"), new BigDecimal("2500")}
            ));
        lenient().when(accountService.liveBalanceEur(acc1)).thenReturn(new BigDecimal("1200"));
        lenient().when(accountService.liveBalanceEur(acc2)).thenReturn(new BigDecimal("2800"));
        lenient().when(accountService.calculateInvestedAmount(acc1)).thenReturn(new BigDecimal("1000"));
        lenient().when(accountService.calculateInvestedAmount(acc2)).thenReturn(new BigDecimal("2500"));

        List<NetWorthPoint> result = historyService.buildHistory(List.of(1L, 2L), 1, true, MEMBER_ID);

        NetWorthPoint atDate = result.stream()
            .filter(p -> p.date().equals(date))
            .findFirst()
            .orElseThrow();
        assertThat(atDate.accounts().get(1L).invested()).isEqualByComparingTo("1000");
        assertThat(atDate.accounts().get(2L).invested()).isEqualByComparingTo("2500");
        assertThat(atDate.invested()).isEqualByComparingTo("3500");
        assertThat(atDate.total()).isEqualByComparingTo("4000");
    }

    @Test
    void buildHistory_rejectsAccountsOwnedByAnotherMember() {
        Account othersAccount = brokerage(1L, "CT"); // belongs to MEMBER (id 99)
        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(othersAccount));

        // A different member must not be able to read account 1's history.
        assertThatThrownBy(() -> historyService.buildHistory(List.of(1L), 1, false, 7L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void buildHistory_rejectsNullMemberId() {
        Account account = brokerage(1L, "CT");
        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(account));

        // Member scoping is mandatory — a null memberId is a programming error,
        // not a signal to return every requested account unscoped.
        assertThatThrownBy(() -> historyService.buildHistory(List.of(1L), 1, false, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
