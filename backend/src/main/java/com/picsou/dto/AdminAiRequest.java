package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;

/** Admin AI-provider config write. apiKey blank/omitted = keep the existing stored key. provider
 *  may be "none" to disable. */
public record AdminAiRequest(@NotBlank String provider, String model, String baseUrl, String apiKey) {}
