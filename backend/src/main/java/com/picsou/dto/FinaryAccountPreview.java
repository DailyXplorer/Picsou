package com.picsou.dto;

import com.picsou.model.AccountType;

public record FinaryAccountPreview(
    String finaryId,
    String finaryName,
    String finaryInstitution,
    String finaryCategory,
    AccountType suggestedType,
    double currentBalance,
    String nativeCurrency,
    int transactionCount
) {}
