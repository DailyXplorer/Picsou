package com.picsou.finary.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the JSON returned by Finary's {@code /loans} endpoint parses into
 * {@link FinaryLoanDto} records (issue #11). No network: pure Jackson deserialization.
 */
class FinaryLoanDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loansEnvelope_parsesSnakeCaseLoanEntries() throws Exception {
        // Best-effort shape from issue #11's sample payload.
        String json = """
            {
              "result": [
                {
                  "id": "loan-1",
                  "type": "loan",
                  "name": "PRET CONSO Auto",
                  "outstanding_amount": 12345.67,
                  "monthly_repayment": 250.00,
                  "start_date": "2023-01-01",
                  "end_date": "2028-01-01",
                  "currency": { "code": "EUR", "symbol": "€" },
                  "institution": { "id": "bank-1", "name": "Boursorama", "slug": "bourso" }
                }
              ]
            }
            """;

        FinaryEnvelope<List<FinaryLoanDto>> envelope = objectMapper.readValue(json,
            objectMapper.getTypeFactory().constructParametricType(
                FinaryEnvelope.class,
                objectMapper.getTypeFactory().constructCollectionType(List.class, FinaryLoanDto.class)));

        assertThat(envelope.result()).hasSize(1);
        FinaryLoanDto loan = envelope.result().get(0);
        assertThat(loan.id()).isEqualTo("loan-1");
        assertThat(loan.type()).isEqualTo("loan");
        assertThat(loan.name()).isEqualTo("PRET CONSO Auto");
        assertThat(loan.outstandingAmount()).isEqualTo(12345.67);
        assertThat(loan.monthlyRepayment()).isEqualTo(250.00);
        assertThat(loan.startDate()).isEqualTo("2023-01-01");
        assertThat(loan.endDate()).isEqualTo("2028-01-01");
        assertThat(loan.currency().code()).isEqualTo("EUR");
        assertThat(loan.institution().name()).isEqualTo("Boursorama");
    }

    @Test
    void loanEntry_acceptsCamelCaseAliasesAsFallback() throws Exception {
        String json = """
            {
              "id": "loan-2",
              "type": "loan",
              "name": "Mortgage",
              "outstandingAmount": 99000.0,
              "monthlyRepayment": 800.0,
              "startDate": "2020-06-01",
              "endDate": "2045-06-01"
            }
            """;

        FinaryLoanDto loan = objectMapper.readValue(json, FinaryLoanDto.class);

        assertThat(loan.outstandingAmount()).isEqualTo(99000.0);
        assertThat(loan.monthlyRepayment()).isEqualTo(800.0);
        assertThat(loan.startDate()).isEqualTo("2020-06-01");
        assertThat(loan.endDate()).isEqualTo("2045-06-01");
    }
}
