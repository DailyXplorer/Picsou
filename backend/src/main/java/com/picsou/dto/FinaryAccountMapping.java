package com.picsou.dto;

import com.picsou.model.AccountType;

public record FinaryAccountMapping(
    String finaryId,
    String finaryName,
    String finaryCategory,
    FinaryMappingAction action,
    Long targetAccountId,
    NewAccountDetails newAccount
) {}
