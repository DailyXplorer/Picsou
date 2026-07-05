package com.picsou.dto;

import com.picsou.model.Account;
import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(
    Long id,
    String name,
    AccountType type,
    String provider,
    String currency,
    BigDecimal currentBalance,
    BigDecimal currentBalanceEur,
    Instant lastSyncedAt,
    boolean isManual,
    String color,
    String ticker,
    String logoUrl,
    Instant createdAt,
    RealEstateMetadataResponse realEstate,
    DebtResponse debt,
    SavingsConfigDto savingsConfig,
    /** Non-null only for Revolut pocket sub-accounts; the parent wallet's account id. */
    Long parentAccountId
) {
    public static AccountResponse from(Account a, BigDecimal balanceEur) {
        return new AccountResponse(
            a.getId(),
            a.getName(),
            a.getType(),
            a.getProvider(),
            a.getCurrency(),
            a.getCurrentBalance(),
            balanceEur,
            a.getLastSyncedAt(),
            a.isManual(),
            a.getColor(),
            a.getTicker(),
            a.getLogoUrl(),
            a.getCreatedAt(),
            null,
            null,
            null,
            a.getParentAccountId()
        );
    }

    public AccountResponse withRealEstate(RealEstateMetadataResponse realEstate) {
        return new AccountResponse(id, name, type, provider, currency, currentBalance,
            currentBalanceEur, lastSyncedAt, isManual, color, ticker, logoUrl, createdAt, realEstate, debt,
            savingsConfig, parentAccountId);
    }

    public AccountResponse withDebt(DebtResponse debt) {
        return new AccountResponse(id, name, type, provider, currency, currentBalance,
            currentBalanceEur, lastSyncedAt, isManual, color, ticker, logoUrl, createdAt, realEstate, debt,
            savingsConfig, parentAccountId);
    }

    public AccountResponse withSavingsConfig(SavingsConfigDto savingsConfig) {
        return new AccountResponse(id, name, type, provider, currency, currentBalance,
            currentBalanceEur, lastSyncedAt, isManual, color, ticker, logoUrl, createdAt, realEstate, debt,
            savingsConfig, parentAccountId);
    }
}
