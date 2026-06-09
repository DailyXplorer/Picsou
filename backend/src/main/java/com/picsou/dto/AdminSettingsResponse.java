package com.picsou.dto;
import java.util.List;
import java.util.Map;
public record AdminSettingsResponse(
    SecuritySettings security,
    EnableBankingSettings enableBanking,
    Map<String, Boolean> integrations
) {
    public record SecuritySettings(List<String> allowedOrigins, boolean secureCookies) {}
    public record EnableBankingSettings(String applicationId, String redirectUri, boolean privateKeyPresent) {}
}
