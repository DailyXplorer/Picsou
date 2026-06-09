package com.picsou.dto;
import jakarta.validation.constraints.NotBlank;
public record AdminEnableBankingRequest(
    @NotBlank String applicationId,
    @NotBlank String redirectUri
) {}
