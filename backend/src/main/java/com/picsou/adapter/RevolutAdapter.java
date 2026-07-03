package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.exception.SyncException;
import com.picsou.model.AccountType;
import com.picsou.port.RevolutPort;
import com.picsou.service.sync.SyncProgressService;
import com.picsou.service.sync.SyncProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
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
    private final SyncProgressService progressService;

    public RevolutAdapter(
        @Value("${app.revolut-auth.url:http://revolut-auth:8002}") String revolutAuthUrl,
        SyncProgressService progressService
    ) {
        this.sidecarClient = WebClient.builder()
            .baseUrl(revolutAuthUrl)
            .build();
        this.progressService = progressService;
    }

    @Override
    public List<RevolutAccountData> sync(String phoneNumber, String passcode, Long memberId) {
        log.info("Requesting Revolut sync via revolut-auth sidecar for member {}", memberId);

        // Best-effort side-channel: poll the sidecar's phase while the blocking /sync call is in
        // flight, so the frontend (polling the backend) sees a live phase / approval countdown /
        // accounts-found count. Disposed in the finally below; failures never affect the sync.
        Disposable poller = startProgressPolling(memberId);
        JsonNode response;
        try {
            response = sidecarClient.post()
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
                    if (ex.getStatusCode().value() == 409) {
                        log.warn("revolut-auth sidecar reports a sync already in progress (409) for member {}", memberId);
                        return Mono.error(new SyncException("SYNC_IN_PROGRESS"));
                    }
                    log.error("revolut-auth sidecar /sync failed ({}) : {}",
                        ex.getStatusCode(), ex.getResponseBodyAsString());
                    return Mono.error(new SyncException(
                        "Failed to sync Revolut accounts. Please try again later."));
                })
                .timeout(SYNC_TIMEOUT)
                .blockOptional()
                .orElseThrow(() -> new SyncException("No response from the Revolut service. Please try again later."));
        } finally {
            poller.dispose();
        }

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

    /**
     * Polls the sidecar's {@code GET /progress/{member}} every ~2s and forwards each phase into
     * {@link SyncProgressService}. Best-effort: individual poll failures are swallowed
     * ({@code onErrorResume}) and the whole thing no-ops harmlessly when no progress job is
     * registered for the member (e.g. the scheduler's unattended resync, which never calls
     * {@code startIfIdle}).
     */
    private Disposable startProgressPolling(Long memberId) {
        return Flux.interval(Duration.ofSeconds(1), Duration.ofSeconds(2))
            .flatMap(tick -> sidecarClient.get()
                .uri("/progress/{member}", memberId)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .onErrorResume(e -> Mono.empty()))
            .doOnNext(node -> forwardProgress(memberId, node))
            .subscribe();
    }

    private void forwardProgress(Long memberId, JsonNode node) {
        if (node == null || !node.hasNonNull("phase")) {
            return;
        }
        progressService.phase(memberId, SyncProvider.REVOLUT, node.get("phase").asText(),
            node.hasNonNull("remainingSeconds") ? node.get("remainingSeconds").asInt() : null,
            node.hasNonNull("elapsedSeconds") ? node.get("elapsedSeconds").asInt() : null,
            node.hasNonNull("accountsFound") ? node.get("accountsFound").asInt() : null);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText();
    }
}
