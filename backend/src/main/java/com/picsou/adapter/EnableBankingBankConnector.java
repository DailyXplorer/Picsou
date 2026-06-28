package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.exception.SyncException;
import com.picsou.port.BankConnectorPort;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Enable Banking Bank Account Data API connector.
 * https://enablebanking.com/docs/api/reference/
 *
 * Auth: JWT signed with your RSA private key (RS256).
 * Sessions are initiated via OAuth and tracked in the requisition table.
 */
@Component
public class EnableBankingBankConnector implements BankConnectorPort {

    private static final Logger log = LoggerFactory.getLogger(EnableBankingBankConnector.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final EnableBankingConfigProvider configProvider;
    private final WebClient webClient;
    private final int sessionPollAttempts;
    private final int sessionPollDelayMs;

    public EnableBankingBankConnector(
        EnableBankingConfigProvider configProvider,
        @Value("${app.enablebanking.base-url:https://api.enablebanking.com}") String baseUrl,
        @Value("${app.enablebanking.session-poll-attempts:8}") int sessionPollAttempts,
        @Value("${app.enablebanking.session-poll-delay-ms:2000}") int sessionPollDelayMs
    ) {
        this.configProvider = configProvider;
        this.sessionPollAttempts = sessionPollAttempts;
        this.sessionPollDelayMs = sessionPollDelayMs;
        this.webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Accept", "application/json")
            .defaultHeader("Content-Type", "application/json")
            .build();
    }

    private String applicationId() {
        return configProvider.applicationId()
            .orElseThrow(() -> new SyncException(ebMissingMessage("Application ID")));
    }

    private String keyId() {
        return configProvider.keyId()
            .orElseThrow(() -> new SyncException(ebMissingMessage("Key ID")));
    }

    private String redirectUri() {
        return configProvider.redirectUri()
            .orElseThrow(() -> new SyncException(ebMissingMessage("redirect URL")));
    }

    private PrivateKey privateKey() {
        return configProvider.privateKey()
            .orElseThrow(() -> new SyncException(
                "Enable Banking private key is missing on the server. Open Settings → Integrations → " +
                "Enable Banking and generate or import the key pair (the Application ID, Key ID and " +
                "redirect URL are not enough — a private key is also required)."));
    }

    /**
     * Names the single missing credential rather than claiming Enable Banking is
     * entirely unconfigured — the four pieces (Application ID, Key ID, redirect
     * URL, private key) are stored/loaded independently, so a generic message
     * misleads operators who have set everything but the one missing field.
     */
    private static String ebMissingMessage(String field) {
        return "Enable Banking is not fully configured: " + field + " is missing. " +
               "Open Settings → Integrations → Enable Banking in the app, or re-run the setup wizard. " +
               "Free registration: https://enablebanking.com/";
    }

    // ─── BankConnectorPort ────────────────────────────────────────────────────

    @Override
    public InitiateResult initiateConnection(String institutionId) {
        // institutionId format: "BankName::FR" (name::country)
        String[] parts = institutionId.split("::");
        String bankName = parts[0];
        String country = parts.length > 1 ? parts[1] : "FR";

        var body = Map.of(
            "access", Map.of("valid_until", Instant.now().plus(90, ChronoUnit.DAYS).toString()),
            "aspsp", Map.of("name", bankName, "country", country),
            "state", applicationId() + "_" + System.currentTimeMillis(),
            "redirect_url", redirectUri(),
            "psu_type", "personal"
        );

        AuthStartResponse auth = webClient.post()
            .uri("/auth")
            .header("Authorization", "Bearer " + buildJwt())
            .bodyValue(body)
            .retrieve()
            .bodyToMono(AuthStartResponse.class)
            .timeout(TIMEOUT)
            .onErrorMap(WebClientResponseException.class,
                ex -> new SyncException("Enable Banking auth failed: " + ex.getResponseBodyAsString(), ex))
            .onErrorMap(ex -> !(ex instanceof SyncException),
                ex -> new SyncException("Enable Banking auth error: " + ex.getMessage(), ex))
            .block();

        if (auth == null || auth.url() == null) {
            throw new SyncException("Empty response from Enable Banking /auth");
        }

        log.info("Enable Banking auth initiated");
        return new InitiateResult(auth.authorizationId(), auth.url());
    }

    @Override
    public String exchangeCode(String oauthCode) {
        log.info("Exchanging OAuth code for Enable Banking session");
        SessionCreateResponse session = webClient.post()
            .uri("/sessions")
            .header("Authorization", "Bearer " + buildJwt())
            .bodyValue(Map.of("code", oauthCode))
            .retrieve()
            .bodyToMono(SessionCreateResponse.class)
            .timeout(TIMEOUT)
            .onErrorMap(WebClientResponseException.class,
                ex -> new SyncException("Enable Banking code exchange failed: " + ex.getResponseBodyAsString(), ex))
            .onErrorMap(ex -> !(ex instanceof SyncException),
                ex -> new SyncException("Enable Banking code exchange error: " + ex.getMessage(), ex))
            .block();

        if (session == null || session.sessionId() == null) {
            throw new SyncException("Empty session response from Enable Banking /sessions");
        }

        log.info("Enable Banking session created: {}", session.sessionId());
        return session.sessionId();
    }

    @Override
    public List<AccountData> fetchBalances(String sessionId) {
        List<String> accounts = fetchSessionAccountsWithRetry(sessionId);
        return accounts.stream()
            .map(accountId -> fetchAccountData(accountId))
            .toList();
    }

    /**
     * Polls GET /sessions/{id} until accounts are populated. Enable Banking
     * links accounts asynchronously after OAuth — usually a few seconds, but
     * occasionally longer.
     *
     * <p>The poll window is configurable via {@code app.enablebanking.session-poll-attempts}
     * (default 8) and {@code app.enablebanking.session-poll-delay-ms} (default 2000), giving a
     * ~16 s worst-case wall time — long enough for slow ASPSPs like Boursorama, which take
     * longer than the former 4.5 s window to finish async account linking, while still under a
     * typical reverse-proxy {@code proxy_read_timeout}. Operators behind a stricter proxy can
     * tune these down without a redeploy. If the session still hasn't been populated by then, we
     * return an empty list rather than throw: the caller keeps the requisition retryable so the
     * user (and the scheduler) can retry from the UI without losing the session id. Throwing here
     * turned the legitimate "still linking" case into a 502 in production.
     */
    private List<String> fetchSessionAccountsWithRetry(String sessionId) {
        int maxAttempts = sessionPollAttempts;
        int delayMs = sessionPollDelayMs;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            SessionResponse session = webClient.get()
                .uri("/sessions/{id}", sessionId)
                .header("Authorization", "Bearer " + buildJwt())
                .retrieve()
                .bodyToMono(SessionResponse.class)
                .timeout(TIMEOUT)
                .onErrorMap(WebClientResponseException.class,
                    ex -> new SyncException("Failed to fetch session: " + ex.getResponseBodyAsString(), ex))
                .block();

            if (session != null && session.accounts() != null && !session.accounts().isEmpty()) {
                log.info("Session {} has {} accounts (attempt {}, status={})",
                    sessionId, session.accounts().size(), attempt, session.status());
                return session.accounts();
            }

            log.info("Session {} has no accounts yet (attempt {}/{}, status={})",
                sessionId, attempt, maxAttempts,
                session != null ? session.status() : "null");

            if (attempt < maxAttempts) {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }

        log.warn("Session {} still has no accounts after {} attempts — returning empty so the caller can retry asynchronously",
            sessionId, maxAttempts);
        return List.of();
    }

    @Override
    public List<TransactionData> fetchTransactions(String sessionId, String externalAccountId, LocalDate from) {
        List<TransactionData> result = new ArrayList<>();
        String continuationKey = null;
        int maxPages = 20; // safety bound — ~20 pages of history is plenty for a sync

        for (int page = 0; page < maxPages; page++) {
            final String contKey = continuationKey;
            TransactionsResponse response;
            try {
                response = webClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder.path("/accounts/{id}/transactions")
                            .queryParam("date_from", from.toString());
                        if (contKey != null) b.queryParam("continuation_key", contKey);
                        return b.build(externalAccountId);
                    })
                    .header("Authorization", "Bearer " + buildJwt())
                    .retrieve()
                    .bodyToMono(TransactionsResponse.class)
                    .timeout(TIMEOUT)
                    .block();
            } catch (WebClientResponseException ex) {
                // Some ASPSPs / account types don't expose transactions — treat as "none"
                // rather than failing the whole sync (balances already succeeded).
                log.warn("Transaction fetch failed for account {} ({}): {}",
                    externalAccountId, ex.getStatusCode(), ex.getResponseBodyAsString());
                break;
            }

            if (response == null || response.transactions() == null) break;

            for (TransactionItem t : response.transactions()) {
                TransactionData mapped = mapTransaction(t);
                if (mapped != null) result.add(mapped);
            }

            continuationKey = response.continuationKey();
            if (continuationKey == null || continuationKey.isBlank()) break;
        }

        log.info("Fetched {} transactions for account {} since {}", result.size(), externalAccountId, from);
        return result;
    }

    /** Maps one Enable Banking transaction, signing the amount and picking the counterparty. */
    private TransactionData mapTransaction(TransactionItem t) {
        if (t.transactionAmount() == null || t.transactionAmount().amount() == null) {
            return null;
        }
        boolean isDebit = "DBIT".equalsIgnoreCase(t.creditDebitIndicator());
        BigDecimal amount = new BigDecimal(t.transactionAmount().amount());
        if (isDebit) {
            amount = amount.negate(); // outflow → negative, matching manual-entry convention
        }
        String currency = t.transactionAmount().currency() != null ? t.transactionAmount().currency() : "EUR";

        // Counterparty is whoever is on the other side of the flow.
        String counterparty = isDebit
            ? nameOf(t.creditor())
            : nameOf(t.debtor());

        String description = t.remittanceInformation() != null && !t.remittanceInformation().isEmpty()
            ? String.join(" ", t.remittanceInformation()).trim()
            : counterparty;

        LocalDate date = t.bookingDate() != null ? LocalDate.parse(t.bookingDate())
            : t.valueDate() != null ? LocalDate.parse(t.valueDate())
            : LocalDate.now();

        String externalId = t.entryReference() != null ? t.entryReference() : t.transactionId();
        if (externalId == null) {
            // No bank-provided id — synthesize a stable one so dedup still works across syncs.
            externalId = "syn-" + date + "-" + amount.stripTrailingZeros().toPlainString()
                + "-" + (counterparty != null ? counterparty.hashCode() : 0);
        }

        return new TransactionData(externalId, date, amount, currency, counterparty, description);
    }

    private static String nameOf(TransactionParty party) {
        return party != null ? party.name() : null;
    }

    @Override
    public List<InstitutionData> searchInstitutions(String query, String country) {
        log.info("Searching institutions: query='{}' country='{}'", query, country);
        AspspsResponse response = webClient.get()
            .uri(uriBuilder -> {
                var b = uriBuilder.path("/aspsps").queryParam("psu_type", "personal");
                if (country != null && !country.isBlank()) b.queryParam("country", country);
                return b.build();
            })
            .header("Authorization", "Bearer " + buildJwt())
            .retrieve()
            .bodyToMono(AspspsResponse.class)
            .timeout(TIMEOUT)
            .onErrorMap(WebClientResponseException.class,
                ex -> new SyncException("Failed to fetch institutions: [" + ex.getStatusCode() + "] " + ex.getResponseBodyAsString(), ex))
            .onErrorMap(ex -> !(ex instanceof SyncException),
                ex -> new SyncException("Failed to fetch institutions: " + ex.getMessage(), ex))
            .block();

        int count = (response != null && response.aspsps() != null) ? response.aspsps().size() : 0;
        log.info("Enable Banking returned {} ASPSPs", count);

        List<AspspResponse> aspsps = (response != null && response.aspsps() != null) ? response.aspsps() : List.of();

        if (aspsps == null) return List.of();

        String q = query != null ? query.toLowerCase() : "";
        return aspsps.stream()
            .filter(a -> q.isEmpty() || (a.name() != null && a.name().toLowerCase().contains(q)))
            .map(a -> new InstitutionData(
                a.name() + "::" + (a.country() != null ? a.country() : country != null ? country : ""),
                a.name(),
                a.bic(),
                a.logo(),
                a.country() != null ? a.country() : country
            ))
            .limit(20)
            .toList();
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private AccountData fetchAccountData(String accountId) {
        BalancesResponse balances = webClient.get()
            .uri("/accounts/{id}/balances", accountId)
            .header("Authorization", "Bearer " + buildJwt())
            .retrieve()
            .bodyToMono(BalancesResponse.class)
            .timeout(TIMEOUT)
            .block();

        AccountDetailsResponse details = webClient.get()
            .uri("/accounts/{id}/details", accountId)
            .header("Authorization", "Bearer " + buildJwt())
            .retrieve()
            .bodyToMono(AccountDetailsResponse.class)
            .timeout(TIMEOUT)
            .block();

        BigDecimal balance = BigDecimal.ZERO;
        String currency = "EUR";
        String name = "Account";
        String iban = null;

        log.info("Balances for account {}: {}", accountId, balances);
        if (balances != null && balances.balances() != null && !balances.balances().isEmpty()) {
            var b = balances.balances().stream()
                .filter(bl -> "closingBooked".equals(bl.balanceType()) || "expected".equals(bl.balanceType()))
                .findFirst()
                .orElse(balances.balances().get(0));
            if (b.balanceAmount() != null) {
                balance = new BigDecimal(b.balanceAmount().amount());
                currency = b.balanceAmount().currency();
            }
        }

        if (details != null && details.account() != null) {
            name = details.account().name() != null ? details.account().name() :
                   details.account().product() != null ? details.account().product() : "Account";
            iban = details.account().iban();
        }

        return new AccountData(accountId, name, iban, currency, balance);
    }

    /**
     * Build a short-lived RS256 JWT to authenticate API calls.
     * https://enablebanking.com/docs/api/reference/#section/Authentication
     */
    String buildJwt() {
        Instant now = Instant.now();
        return Jwts.builder()
            .header().keyId(keyId()).and()
            .issuer(applicationId())
            .claim("aud", "api.enablebanking.com")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(3600)))
            .signWith(privateKey(), Jwts.SIG.RS256)
            .compact();
    }

    // ─── Enable Banking API response types ───────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthStartResponse(
        String url,
        @com.fasterxml.jackson.annotation.JsonProperty("authorization_id") String authorizationId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SessionCreateResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("session_id") String sessionId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SessionResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("session_id") String sessionId,
        String url,
        String status,
        List<String> accounts
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AspspsResponse(List<AspspResponse> aspsps) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AspspResponse(String name, String bic, String logo, String country) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BalancesResponse(List<BalanceItem> balances) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record BalanceItem(
            @com.fasterxml.jackson.annotation.JsonProperty("balance_amount") BalanceAmount balanceAmount,
            @com.fasterxml.jackson.annotation.JsonProperty("balance_type") String balanceType
        ) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        record BalanceAmount(String amount, String currency) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionsResponse(
        List<TransactionItem> transactions,
        @com.fasterxml.jackson.annotation.JsonProperty("continuation_key") String continuationKey
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionItem(
        @com.fasterxml.jackson.annotation.JsonProperty("entry_reference") String entryReference,
        @com.fasterxml.jackson.annotation.JsonProperty("transaction_id") String transactionId,
        @com.fasterxml.jackson.annotation.JsonProperty("booking_date") String bookingDate,
        @com.fasterxml.jackson.annotation.JsonProperty("value_date") String valueDate,
        @com.fasterxml.jackson.annotation.JsonProperty("transaction_amount") TransactionAmount transactionAmount,
        @com.fasterxml.jackson.annotation.JsonProperty("credit_debit_indicator") String creditDebitIndicator,
        TransactionParty creditor,
        TransactionParty debtor,
        @com.fasterxml.jackson.annotation.JsonProperty("remittance_information") List<String> remittanceInformation
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionAmount(String amount, String currency) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionParty(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountDetailsResponse(AccountDetail account) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record AccountDetail(
            String iban,
            String name,
            String product,
            @com.fasterxml.jackson.annotation.JsonProperty("cash_account_type") String cashAccountType
        ) {}
    }
}
