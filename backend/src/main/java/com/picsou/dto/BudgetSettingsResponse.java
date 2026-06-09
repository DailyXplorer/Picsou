package com.picsou.dto;

import com.picsou.model.BudgetSettings;

import java.time.LocalDate;

public record BudgetSettingsResponse(
    int cycleStartDay,
    boolean logoFetchEnabled,
    LocalDate currentCycleStart,
    LocalDate currentCycleEnd
) {
    public static BudgetSettingsResponse of(BudgetSettings s, LocalDate cycleStart, LocalDate cycleEnd) {
        return new BudgetSettingsResponse(s.getCycleStartDay(), s.isLogoFetchEnabled(), cycleStart, cycleEnd);
    }
}
