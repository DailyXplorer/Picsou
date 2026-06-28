package com.picsou.service;

import com.picsou.dto.DebtRequest;
import com.picsou.dto.HoldingResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.RealEstateMetadataRepository;
import com.picsou.repository.SavingsInterestConfigRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock RealEstateMetadataRepository realEstateMetadataRepository;
    @Mock DebtRepository debtRepository;
    @Mock SavingsInterestConfigRepository savingsInterestConfigRepository;
    @Mock PriceService priceService;
    @Mock LoanAmortizationService loanAmortizationService;
    @InjectMocks AccountService accountService;

    private Account ownedAccount() {
        return Account.builder()
            .id(1L)
            .name("TR Titres")
            .type(AccountType.COMPTE_TITRES)
            .currency("EUR")
            .build();
    }

    @Test
    void getHoldings_returnsNullValue_whenPriceServiceHasNoPrice() {
        when(accountRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(ownedAccount()));
        AccountHolding holding = AccountHolding.builder()
            .id(10L)
            .ticker("PHYMF")
            .quantity(new BigDecimal("10"))
            .averageBuyIn(new BigDecimal("100"))
            // Stored from a broker sync in unknown currency — must NOT be used as EUR.
            .currentPrice(new BigDecimal("999"))
            .build();
        when(holdingRepository.findByAccountIdOrderByCurrentPriceDesc(1L))
            .thenReturn(List.of(holding));
        when(priceService.getPriceEur("PHYMF")).thenReturn(null);

        List<HoldingResponse> result = accountService.getHoldings(1L, 1L);

        assertThat(result).hasSize(1);
        HoldingResponse h = result.get(0);
        // The key invariant: no fallback to holding.currentPrice (999) × quantity (10) = 9990.
        assertThat(h.currentValueEur()).isNull();
        assertThat(h.pnlEur()).isNull();
        assertThat(h.pnlPercent()).isNull();
    }

    @Test
    void getHoldings_computesValue_whenPriceServiceHasPrice() {
        when(accountRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(ownedAccount()));
        AccountHolding holding = AccountHolding.builder()
            .id(10L)
            .ticker("AAPL")
            .quantity(new BigDecimal("5"))
            .averageBuyIn(new BigDecimal("150"))
            .currentPrice(new BigDecimal("180"))  // native-currency, must be ignored
            .build();
        when(holdingRepository.findByAccountIdOrderByCurrentPriceDesc(1L))
            .thenReturn(List.of(holding));
        // Yahoo returned 200 EUR/share after FX conversion (e.g. ~217 USD × 0.92).
        when(priceService.getPriceEur("AAPL")).thenReturn(new BigDecimal("200"));

        List<HoldingResponse> result = accountService.getHoldings(1L, 1L);

        HoldingResponse h = result.get(0);
        assertThat(h.currentValueEur()).isEqualByComparingTo("1000"); // 5 × 200
        assertThat(h.pnlEur()).isEqualByComparingTo("250"); // 1000 − (5 × 150)
        assertThat(h.pnlPercent().doubleValue()).isCloseTo(33.33, within(0.1));
    }

    @Test
    void updateDebtMetadata_rejectsLinkedAccount_notOwnedByMember() {
        // Caller (member 1) owns the loan account (id 1)...
        when(accountRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(ownedAccount()));
        when(debtRepository.findByAccountId(1L)).thenReturn(Optional.empty());
        // ...but points linkedAccountId at account 2, which is NOT theirs: the member-scoped
        // lookup finds nothing. Previously this used an unscoped findById, leaking the foreign
        // account's name back via DebtResponse (BOLA). It must now be rejected.
        when(accountRepository.findByIdAndMemberId(2L, 1L)).thenReturn(Optional.empty());

        DebtRequest req = new DebtRequest(
            2L, new BigDecimal("100000"), new BigDecimal("0.03"), new BigDecimal("500"),
            "Bank", null, null, null, null);

        assertThatThrownBy(() -> accountService.updateDebtMetadata(1L, 1L, req))
            .isInstanceOf(ResourceNotFoundException.class);
        // The cross-member reference must never be persisted.
        verify(debtRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
