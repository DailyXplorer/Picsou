package com.picsou.finary.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A loan / mortgage entry returned by Finary's dedicated {@code /loans} endpoint.
 *
 * <p>Loans are <strong>not</strong> exposed through the portfolio
 * {@code credits}/{@code credit_accounts} categories, so they have to be fetched
 * separately (see issue #11).
 *
 * <p>Field names are best-effort from the sample payload reported in issue #11
 * ({@code type}, {@code name}, {@code outstanding_amount}, {@code monthly_repayment},
 * {@code start_date}, {@code end_date}). The snake_case keys are mapped explicitly via
 * {@link JsonProperty}; camelCase aliases are accepted as a fallback because the rest of
 * the Finary API returns camelCase. Unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FinaryLoanDto(
    String id,
    String type,
    String name,
    @JsonProperty("outstanding_amount") @JsonAlias("outstandingAmount") Double outstandingAmount,
    @JsonProperty("monthly_repayment") @JsonAlias("monthlyRepayment") Double monthlyRepayment,
    @JsonProperty("start_date") @JsonAlias("startDate") String startDate,
    @JsonProperty("end_date") @JsonAlias("endDate") String endDate,
    FinaryAccountCurrency currency,
    FinaryAccountInstitution institution
) {}
