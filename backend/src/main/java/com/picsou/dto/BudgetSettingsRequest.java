package com.picsou.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record BudgetSettingsRequest(
    @Min(1) @Max(28) int cycleStartDay,
    boolean logoFetchEnabled
) {}
