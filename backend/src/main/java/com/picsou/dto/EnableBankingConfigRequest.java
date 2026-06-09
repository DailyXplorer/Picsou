package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Wizard payload for Step 3a.2 "Credentials". The UUID-ish shape regex is a
 * soft guard — the authoritative validation is the real auth call we make
 * at Step 3a.5 with these values. We keep the regex permissive
 * ({@code [0-9a-fA-F-]{32,36}}) because Enable Banking's exact format has
 * drifted in the past and we do not want fresh registrations to be rejected
 * by us before EB itself sees them.
 */
public record EnableBankingConfigRequest(
    @NotBlank
    @Pattern(regexp = "^[0-9a-fA-F-]{32,36}$",
             message = "Application ID must look like a UUID (32-36 hex characters)")
    String applicationId,

    @NotBlank
    @Size(max = 500)
    @Pattern(regexp = "^https?://.+", message = "Redirect URI must start with http:// or https://")
    String redirectUri
) {}
