package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts ISIN codes to Yahoo Finance ticker symbols using the free OpenFIGI API.
 *
 * Trade Republic returns ISIN codes (e.g. IE00BYVQ9F29) but Yahoo Finance expects
 * ticker symbols (e.g. IWDA.AS, MC.PA). This adapter fills that gap.
 *
 * OpenFIGI API: https://www.openfigi.com/api
 * No authentication required. Rate limit: 25 requests/min without API key.
 */
@Component
public class OpenFigiIsinConverter {

    private static final Logger log = LoggerFactory.getLogger(OpenFigiIsinConverter.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Result of an ISIN conversion: Yahoo ticker + display name. */
    public record TickerResult(String ticker, String name) {}

    /** ISIN format: 2-letter country code + 9 alphanumerics + 1 check digit. */
    private static final java.util.regex.Pattern ISIN_PATTERN =
        java.util.regex.Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[A-Z0-9]");

    /**
     * Whether {@code s} looks like an ISIN (2-letter country code + 9 alphanumerics
     * + 1 check digit = 12 chars). Case-insensitive; trims surrounding whitespace.
     * Mirrors the detection in {@code YahooFinancePriceProvider.supports()}.
     */
    public static boolean isIsin(String s) {
        if (s == null) {
            return false;
        }
        String upper = s.trim().toUpperCase();
        return upper.length() == 12 && ISIN_PATTERN.matcher(upper).matches();
    }

    /** OpenFIGI exchCode → Yahoo Finance exchange suffix. Empty string = no suffix (US markets). */
    private static final Map<String, String> EXCHANGE_SUFFIX = Map.ofEntries(
        // US — no suffix
        Map.entry("US", ""),  Map.entry("UN", ""), Map.entry("UA", ""),
        Map.entry("UC", ""),  Map.entry("UD", ""), Map.entry("UW", ""),
        Map.entry("NQ", ""),  Map.entry("NY", ""),
        Map.entry("OQ", ""),  Map.entry("PK", ""), Map.entry("PQ", ""),
        // Germany — .DE
        Map.entry("GR", ".DE"), Map.entry("GF", ".DE"), Map.entry("GD", ".DE"),
        Map.entry("GY", ".DE"), Map.entry("GS", ".DE"), Map.entry("GM", ".DE"),
        Map.entry("GT", ".DE"), Map.entry("GI", ".DE"), Map.entry("GH", ".DE"),
        Map.entry("GZ", ".DE"), Map.entry("TH", ".DE"), Map.entry("QT", ".DE"),
        // France — .PA
        Map.entry("FP", ".PA"), Map.entry("PA", ".PA"),
        // Netherlands — .AS
        Map.entry("NA", ".AS"),
        // UK — .L
        Map.entry("LN", ".L"),
        // Italy — .MI
        Map.entry("IM", ".MI"),
        // Belgium — .BR
        Map.entry("BR", ".BR"),
        // Switzerland — .SW
        Map.entry("SW", ".SW"), Map.entry("SZ", ".SW"),
        // Spain — .MC
        Map.entry("SM", ".MC"),
        // Canada — .TO
        Map.entry("TO", ".TO"), Map.entry("TV", ".TO"),
        // Japan — .T
        Map.entry("JT", ".T"),
        // Hong Kong — .HK
        Map.entry("HK", ".HK"),
        // Australia — .AX
        Map.entry("AU", ".AX"),
        // Singapore — .SI
        Map.entry("SG", ".SI"),
        // India — .NS
        Map.entry("IN", ".NS"),
        // Korea — .KS
        Map.entry("KS", ".KS")
    );

    /** Preferred EU exchanges for fallback. */
    private static final List<String> EU_PREFERRED = List.of(
        "NA", "FP", "GY", "GR", "GF", "LN", "IM", "BR"
    );

    /** ISIN country prefix → preferred exchange code for that market. */
    private static final Map<String, String> HOME_EXCHANGE = Map.ofEntries(
        Map.entry("US", "US"),  Map.entry("HK", "HK"),  Map.entry("JP", "JT"),
        Map.entry("AU", "AU"),  Map.entry("SG", "SG"),  Map.entry("IN", "IN"),
        Map.entry("KR", "KS"),  Map.entry("GB", "LN"),  Map.entry("DE", "GY"),
        Map.entry("FR", "FP"),  Map.entry("NL", "NA"),  Map.entry("IT", "IM"),
        Map.entry("CH", "SW"),  Map.entry("ES", "SM"),  Map.entry("CA", "TO")
    );

    private final WebClient webClient;
    // Cache: ISIN → TickerResult. Null value means conversion failed.
    private final Map<String, TickerResult> cache = new ConcurrentHashMap<>();

    public OpenFigiIsinConverter() {
        this.webClient = WebClient.builder()
            .baseUrl("https://api.openfigi.com")
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("Accept", "application/json")
            .build();
    }

    /**
     * Converts an ISIN to a Yahoo Finance ticker + display name.
     *
     * Returns a TickerResult with the original ISIN as ticker and null name
     * if conversion fails — callers should handle gracefully.
     *
     * @param isin The ISIN code (e.g. "IE00BYVQ9F29")
     * @return result with Yahoo ticker (e.g. "IWDA.AS") and name, or ISIN as fallback
     */
    public TickerResult resolve(String isin) {
        if (isin == null || isin.isBlank()) {
            return new TickerResult(isin, null);
        }

        // Check cache first
        TickerResult cached = cache.get(isin);
        if (cached != null) {
            log.debug("ISIN {} resolved from cache -> {} ({})", isin, cached.ticker, cached.name);
            return cached;
        }

        try {
            TickerResult result = fetchFromOpenFigi(isin);
            if (result != null) {
                cache.put(isin, result);
                log.info("ISIN {} resolved via OpenFIGI -> {} ({})", isin, result.ticker, result.name);
                return result;
            } else {
                // Cache a fallback result so we don't retry
                TickerResult fallback = new TickerResult(isin, null);
                cache.put(isin, fallback);
                log.warn("OpenFIGI returned no ticker for ISIN {}, will use ISIN as-is", isin);
                return fallback;
            }
        } catch (Exception ex) {
            TickerResult fallback = new TickerResult(isin, null);
            cache.put(isin, fallback);
            log.warn("Failed to convert ISIN {} via OpenFIGI: {}, will use ISIN as-is",
                     isin, ex.getMessage());
            return fallback;
        }
    }

    private TickerResult fetchFromOpenFigi(String isin) {
        List<MappingJob> request = List.of(new MappingJob("ID_ISIN", isin));

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> responses = webClient.post()
                .uri("/v3/mapping")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(List.class)
                .timeout(TIMEOUT)
                .block();

            if (responses == null || responses.isEmpty()) {
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> first = (Map<String, Object>) responses.get(0);

            if (first.containsKey("error")) {
                log.warn("OpenFIGI error for ISIN {}: {}", isin, first.get("error"));
                return null;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) first.get("data");

            if (data == null || data.isEmpty()) {
                return null;
            }

            return pickBest(isin, data);
        } catch (Exception ex) {
            log.warn("OpenFIGI API request failed for ISIN {}: {}", isin, ex.getMessage());
            return null;
        }
    }

    /**
     * Picks the best Yahoo Finance ticker + name from OpenFIGI results.
     * Strategy:
     * 1. Home exchange (based on ISIN country) — best Yahoo coverage
     * 2. US OTC/ADR — good Yahoo coverage for international stocks
     * 3. EU exchanges — EUR pricing
     * 4. Any known exchange
     */
    private TickerResult pickBest(String isin, List<Map<String, Object>> entries) {
        // Build a map: exchCode → (yahooTicker, name)
        Map<String, String[]> byExchange = new java.util.LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            String ticker = (String) entry.get("ticker");
            String exchCode = (String) entry.get("exchCode");
            String name = (String) entry.get("name");
            if (ticker == null || ticker.isBlank() || exchCode == null) continue;

            String suffix = EXCHANGE_SUFFIX.get(exchCode);
            if (suffix != null && !byExchange.containsKey(exchCode)) {
                byExchange.put(exchCode, new String[]{ ticker + suffix, name });
            }
        }

        String country = isin.length() >= 2 ? isin.substring(0, 2).toUpperCase() : "";

        // 1. Try home exchange
        String home = HOME_EXCHANGE.get(country);
        if (home != null && byExchange.containsKey(home)) {
            String[] r = byExchange.get(home);
            return new TickerResult(r[0], r[1]);
        }

        // 2. For non-US ISINs, try US exchanges (OTC/ADR)
        if (!country.equals("US")) {
            for (String us : List.of("US", "NY", "NQ", "OQ", "PQ")) {
                if (byExchange.containsKey(us)) {
                    String[] r = byExchange.get(us);
                    return new TickerResult(r[0], r[1]);
                }
            }
        }

        // 3. EU exchanges
        for (String eu : EU_PREFERRED) {
            if (byExchange.containsKey(eu)) {
                String[] r = byExchange.get(eu);
                return new TickerResult(r[0], r[1]);
            }
        }

        // 4. Any known exchange
        if (!byExchange.isEmpty()) {
            String[] r = byExchange.values().iterator().next();
            return new TickerResult(r[0], r[1]);
        }

        // 5. Raw ticker from first entry
        for (Map<String, Object> entry : entries) {
            String ticker = (String) entry.get("ticker");
            String name = (String) entry.get("name");
            if (ticker != null && !ticker.isBlank()) {
                return new TickerResult(ticker, name);
            }
        }

        return null;
    }

    record MappingJob(
        @JsonProperty("idType") String idType,
        @JsonProperty("idValue") String idValue
    ) {}
}
