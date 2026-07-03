package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.exception.SyncException;
import com.picsou.model.AccountType;
import com.picsou.port.RevolutPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adapter for the revolut-auth Python sidecar (see docs/features/revolut-sidecar.md).
 *
 * On-demand model: a single {@code POST /sync} call either reuses a still-live per-member browser
 * profile (no login) or performs a fresh automated login (mobile push approval) before harvesting.
 * The reactive timeout below must comfortably exceed the sidecar's own ~300s approval-wait budget.
 */
@Component
public class RevolutAdapter implements RevolutPort {

    private static final Logger log = LoggerFactory.getLogger(RevolutAdapter.class);

    private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(330);

    private final WebClient sidecarClient;

    public RevolutAdapter(
        @Value("${app.revolut-auth.url:http://revolut-auth:8002}") String revolutAuthUrl
    ) {
        this.sidecarClient = WebClient.builder()
            .baseUrl(revolutAuthUrl)
            .build();
    }

    @Override
    public List<RevolutAccountData> sync(String phoneNumber, String passcode, Long memberId) {
        log.info("Requesting Revolut sync via revolut-auth sidecar for member {}", memberId);

        JsonNode response = sidecarClient.post()
            .uri("/sync")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "phoneNumber", phoneNumber,
                "passcode", passcode,
                "memberId", String.valueOf(memberId)))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                if (ex.getStatusCode().value() == 401) {
                    log.warn("revolut-auth sidecar reports session expired (401) for member {}", memberId);
                    return Mono.error(new SyncException("SESSION_EXPIRED"));
                }
                if (ex.getStatusCode().value() == 408) {
                    log.warn("revolut-auth sidecar reports approval timeout (408) for member {}", memberId);
                    return Mono.error(new SyncException("APPROVAL_TIMEOUT"));
                }
                log.error("revolut-auth sidecar /sync failed ({}) : {}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());
                return Mono.error(new SyncException(
                    "Failed to sync Revolut accounts. Please try again later."));
            })
            .timeout(SYNC_TIMEOUT)
            .blockOptional()
            .orElseThrow(() -> new SyncException("No response from the Revolut service. Please try again later."));

        List<RevolutAccountData> accounts = new ArrayList<>();
        for (JsonNode accNode : response.path("accounts")) {
            String externalId = textOrNull(accNode, "externalId");
            String name = textOrNull(accNode, "name");
            AccountType type = "SAVINGS".equals(textOrNull(accNode, "type"))
                ? AccountType.SAVINGS : AccountType.CHECKING;
            String iban = textOrNull(accNode, "iban");
            BigDecimal balance = accNode.path("balance").decimalValue();
            String currency = accNode.hasNonNull("currency") ? accNode.get("currency").asText() : "EUR";
            String parentExternalId = textOrNull(accNode, "parentExternalId");

            List<RevolutTxn> txns = new ArrayList<>();
            for (JsonNode txNode : accNode.path("transactions")) {
                txns.add(new RevolutTxn(
                    textOrNull(txNode, "externalId"),
                    LocalDate.parse(txNode.path("date").asText()),
                    textOrNull(txNode, "description"),
                    txNode.path("amount").decimalValue(),
                    textOrNull(txNode, "counterparty")
                ));
            }

            accounts.add(new RevolutAccountData(
                externalId, name, type, iban, balance, currency, parentExternalId, txns));
        }

        log.info("Revolut sync complete: {} account(s) for member {}", accounts.size(), memberId);
        return accounts;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText();
    }
}
