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
 * Adapter for the revolut-auth Python sidecar (see docs/features/revolut-sidecar.md §4).
 *
 * The sidecar is stateless after auth (like BoursoAdapter): Java hands back the encrypted-at-rest
 * {@code storageState} blob on every call, the sidecar restores the Playwright browser context,
 * refreshes the access token, and harvests the retail API. No token refresh logic lives here --
 * the sidecar owns that internally.
 */
@Component
public class RevolutAdapter implements RevolutPort {

    private static final Logger log = LoggerFactory.getLogger(RevolutAdapter.class);

    private final WebClient sidecarClient;

    public RevolutAdapter(
        @Value("${app.revolut-auth.url:http://revolut-auth:8002}") String revolutAuthUrl
    ) {
        this.sidecarClient = WebClient.builder()
            .baseUrl(revolutAuthUrl)
            .build();
    }

    @Override
    public List<RevolutAccountData> fetchAccounts(String storageState) {
        log.info("Fetching Revolut accounts via revolut-auth sidecar");

        JsonNode response = sidecarClient.post()
            .uri("/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("storageState", storageState))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .onErrorResume(WebClientResponseException.class, ex -> {
                if (ex.getStatusCode().value() == 401) {
                    log.warn("revolut-auth sidecar reports session expired (401)");
                    return Mono.error(new SyncException("SESSION_EXPIRED"));
                }
                log.error("revolut-auth sidecar /accounts failed ({}) : {}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());
                return Mono.error(new SyncException(
                    "Failed to fetch Revolut accounts. Please try again later."));
            })
            .timeout(Duration.ofSeconds(60)) // headless browser + network harvest takes time
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

        log.info("Revolut accounts fetched: {} account(s)", accounts.size());
        return accounts;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText();
    }
}
