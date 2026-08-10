package com.picsou.config;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.HoldingComputeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A manual transaction entered by ISIN keeps the ISIN as its ticker when resolution fails, and
 * nothing ever revisits it — the position then has no price for good, and an account whose every
 * line went that way reads 0 € (GH issue #74). These tests pin what the repair pass may and may
 * not touch.
 */
@ExtendWith(MockitoExtension.class)
class IsinTickerRepairRunnerTest {

    @Mock TransactionRepository transactionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository accountHoldingRepository;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock HoldingComputeService holdingComputeService;
    @Mock PlatformTransactionManager transactionManager;

    @InjectMocks IsinTickerRepairRunner runner;

    @BeforeEach
    void openTransactionsForReal() {
        // The runner applies each ISIN through a TransactionTemplate; lenient because the tests
        // that stop before any write never open one.
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private static final Account PEA = Account.builder()
        .id(1L).name("PEA").type(AccountType.PEA).isManual(true).build();

    private static Transaction tx(String ticker, String name) {
        return Transaction.builder().account(PEA).ticker(ticker).name(name).build();
    }

    private static OpenFigiIsinConverter.TickerResult resolved(String ticker, String name) {
        return new OpenFigiIsinConverter.TickerResult(ticker, name);
    }

    @Test
    void repair_rewritesIsinTickersAndRecomputesTheHoldingsDerivedFromThem() {
        Transaction first = tx("IE000BI8OT95", null);
        Transaction second = tx("IE000BI8OT95", null); // same instrument, bought twice
        when(transactionRepository.findManualTransactionsWithIsinLengthTicker())
            .thenReturn(List.of(first, second));
        when(transactionRepository.findManualAccountIdsByTickerIn(any())).thenReturn(List.of(1L));
        when(isinConverter.resolve("IE000BI8OT95"))
            .thenReturn(resolved("MWRD.PA", "AMUNDI CORE MSCI WORLD"));
        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(PEA));

        runner.repair();

        assertThat(first.getTicker()).isEqualTo("MWRD.PA");
        assertThat(second.getTicker()).isEqualTo("MWRD.PA");
        assertThat(first.getName()).isEqualTo("AMUNDI CORE MSCI WORLD");
        verify(transactionRepository).saveAll(List.of(first, second));
        // Holdings are keyed by ticker and derived from transactions: without this the repaired
        // rows would still aggregate under the old ISIN-keyed holding.
        verify(holdingComputeService).recomputeHoldings(PEA);
    }

    @Test
    void repair_dropsTheHoldingStillKeyedByTheOldIsin() {
        // recomputeHoldings creates the row for the new ticker but does not remove one whose
        // ticker no longer appears in any transaction — leaving the account with the repaired
        // position *and* an orphan that nothing can price, shown with a quantity and no value.
        Transaction row = tx("IE000BI8OT95", null);
        when(transactionRepository.findManualTransactionsWithIsinLengthTicker()).thenReturn(List.of(row));
        when(transactionRepository.findManualAccountIdsByTickerIn(any())).thenReturn(List.of(1L));
        when(isinConverter.resolve("IE000BI8OT95")).thenReturn(resolved("MWRD.PA", null));
        when(accountRepository.findAllById(any())).thenReturn(List.of(PEA));

        runner.repair();

        InOrder inOrder = inOrder(accountHoldingRepository, holdingComputeService);
        inOrder.verify(accountHoldingRepository)
            .deleteByAccountIdInAndTickerIn(List.of(1L), List.of("IE000BI8OT95"));
        inOrder.verify(holdingComputeService).recomputeHoldings(PEA);
    }

    @Test
    void repair_keepsANameTheUserTyped() {
        Transaction named = tx("IE000BI8OT95", "Mon ETF Monde");
        when(transactionRepository.findManualTransactionsWithIsinLengthTicker()).thenReturn(List.of(named));
        when(transactionRepository.findManualAccountIdsByTickerIn(any())).thenReturn(List.of(1L));
        when(isinConverter.resolve("IE000BI8OT95"))
            .thenReturn(resolved("MWRD.PA", "AMUNDI CORE MSCI WORLD"));
        when(accountRepository.findAllById(any())).thenReturn(List.of(PEA));

        runner.repair();

        assertThat(named.getTicker()).isEqualTo("MWRD.PA");
        assertThat(named.getName()).isEqualTo("Mon ETF Monde");
    }

    @Test
    void repair_leavesTheRowUntouchedWhenTheIsinStillDoesNotResolve() {
        // OpenFIGI still down / still no Yahoo listing: resolve() hands back the ISIN itself.
        Transaction stuck = tx("IE000BI8OT95", null);
        when(transactionRepository.findManualTransactionsWithIsinLengthTicker()).thenReturn(List.of(stuck));
        when(isinConverter.resolve("IE000BI8OT95")).thenReturn(resolved("IE000BI8OT95", null));

        runner.repair();

        assertThat(stuck.getTicker()).isEqualTo("IE000BI8OT95");
        verify(transactionRepository, never()).saveAll(any());
        // Nothing changed, so nothing to recompute — and the row is retried on the next boot.
        verifyNoInteractions(holdingComputeService);
    }

    @Test
    void repair_ignoresTwelveCharacterTickersThatAreNotIsins() {
        // The query filters on length alone (SQL cannot run the ISIN check); the shape check is
        // what keeps a legitimate 12-character symbol from being sent to a resolver.
        when(transactionRepository.findManualTransactionsWithIsinLengthTicker())
            .thenReturn(List.of(tx("123456789012", null)));

        runner.repair();

        verifyNoInteractions(isinConverter);
        verify(transactionRepository, never()).saveAll(any());
    }

    @Test
    void repair_doesNothingWhenNoRowCarriesAnIsin() {
        when(transactionRepository.findManualTransactionsWithIsinLengthTicker()).thenReturn(List.of());

        runner.repair();

        verifyNoInteractions(isinConverter, accountRepository, holdingComputeService);
    }

    @Test
    void repair_stopsAtThePerBootLimit_soAStartupIsNotHeldBehindTheProviderRateLimit() {
        // 30 distinct ISINs, OpenFIGI allows 25/min without an API key — and every resolution
        // happens before ApplicationReadyEvent, so the pass is bounded rather than open-ended.
        List<Transaction> rows = new ArrayList<>(IntStream.range(0, 30)
            .mapToObj(i -> tx(String.format("IE00000000%02d", i), null))
            .toList());
        when(transactionRepository.findManualTransactionsWithIsinLengthTicker()).thenReturn(rows);
        when(transactionRepository.findManualAccountIdsByTickerIn(any())).thenReturn(List.of(1L));
        when(isinConverter.resolve(anyString())).thenReturn(resolved("MWRD.PA", null));
        when(accountRepository.findAllById(any())).thenReturn(List.of(PEA));

        runner.repair();

        verify(isinConverter, times(25)).resolve(anyString());

        // In sorted order, so a truncated pass resumes where it stopped instead of re-drawing the
        // same subset on every boot.
        ArgumentCaptor<String> attempted = ArgumentCaptor.captor();
        verify(isinConverter, times(25)).resolve(attempted.capture());
        assertThat(attempted.getAllValues()).startsWith("IE0000000000").isSorted();
    }

    @Test
    void repair_appliesEachIsinInItsOwnTransaction() {
        // Per ISIN, not per pass: renamed rows no longer match the raw-ISIN query, so a failure
        // between the rename and the recompute would strand those holdings with nothing to find
        // them by. Rolling back puts the ISIN back, which is what the next boot looks for.
        when(transactionRepository.findManualTransactionsWithIsinLengthTicker())
            .thenReturn(List.of(tx("IE000BI8OT95", null), tx("IE00B4L5Y983", null)));
        when(transactionRepository.findManualAccountIdsByTickerIn(any())).thenReturn(List.of(1L));
        when(isinConverter.resolve(anyString())).thenReturn(resolved("MWRD.PA", null));
        when(accountRepository.findAllById(any())).thenReturn(List.of(PEA));

        runner.repair();

        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager, times(2)).commit(any());
    }

    @Test
    void repair_doesNotDeriveHoldingsForAnAccountThatIsValuedFromItsBalance() {
        // A BUY row with a ticker can reach a non-investment account through the API. Recomputing
        // it would give that account holdings it never had, switching how it is valued.
        Account checking = Account.builder()
            .id(9L).name("Compte courant").type(AccountType.CHECKING).isManual(true).build();
        Transaction stray = Transaction.builder().account(checking).ticker("IE000BI8OT95").build();
        when(transactionRepository.findManualTransactionsWithIsinLengthTicker()).thenReturn(List.of(stray));
        when(transactionRepository.findManualAccountIdsByTickerIn(any())).thenReturn(List.of(9L));
        when(isinConverter.resolve("IE000BI8OT95")).thenReturn(resolved("MWRD.PA", null));
        when(accountRepository.findAllById(any())).thenReturn(List.of(checking));

        runner.repair();

        assertThat(stray.getTicker()).isEqualTo("MWRD.PA"); // the ticker is still worth repairing
        verifyNoInteractions(holdingComputeService);
    }

    @Test
    void run_neverPreventsTheApplicationFromStarting() {
        when(transactionRepository.findManualTransactionsWithIsinLengthTicker())
            .thenThrow(new RuntimeException("database not reachable yet"));

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }
}
