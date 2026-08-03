package com.picsou.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.YahooFinancePriceProvider;
import com.picsou.repository.PriceSnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceServiceTest {

    @Mock CoinGeckoPriceProvider coinGecko;
    @Mock YahooFinancePriceProvider yahoo;
    @Mock PriceSnapshotRepository priceSnapshotRepository;

    @InjectMocks PriceService priceService;

    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PriceService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private List<ILoggingEvent> eventsAt(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    /**
     * refreshPrices must honor the 15-minute cache TTL: GET /prices is polled
     * by the frontend on an interval, so serving fresh cache entries (instead
     * of re-fetching upstream every call) is what keeps an open dashboard tab
     * from hammering Yahoo/CoinGecko.
     */
    @Test
    void refreshPrices_servesFreshCacheWithoutUpstreamCall() {
        lenient().when(coinGecko.supports(any())).thenReturn(false);
        when(yahoo.getPricesEur(Set.of("AAPL"))).thenReturn(Map.of("AAPL", new BigDecimal("150")));
        lenient().when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());

        Map<String, BigDecimal> first = priceService.refreshPrices(Set.of("AAPL"));
        assertThat(first).containsEntry("AAPL", new BigDecimal("150"));

        Map<String, BigDecimal> second = priceService.refreshPrices(Set.of("AAPL"));
        assertThat(second).containsEntry("AAPL", new BigDecimal("150"));

        verify(yahoo, times(1)).getPricesEur(anySet());
        verify(priceSnapshotRepository, times(1)).save(any());
    }

    @Test
    void refreshPrices_fetchesOnlyMissingTickers() {
        lenient().when(coinGecko.supports(any())).thenReturn(false);
        when(yahoo.getPricesEur(Set.of("AAPL"))).thenReturn(Map.of("AAPL", new BigDecimal("150")));
        lenient().when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());
        priceService.refreshPrices(Set.of("AAPL"));

        when(yahoo.getPricesEur(Set.of("MSFT"))).thenReturn(Map.of("MSFT", new BigDecimal("410")));

        Map<String, BigDecimal> result = priceService.refreshPrices(Set.of("AAPL", "MSFT", "EUR"));

        assertThat(result)
            .containsEntry("AAPL", new BigDecimal("150"))
            .containsEntry("MSFT", new BigDecimal("410"))
            .containsEntry("EUR", BigDecimal.ONE);
        verify(yahoo).getPricesEur(Set.of("MSFT"));
    }

    @Test
    void backfill_continuesPastAFailingTicker_andLogsItAtError() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        when(coinGecko.supports("BTC")).thenReturn(true);
        when(coinGecko.supports("ETH")).thenReturn(true);
        when(coinGecko.getHistoricalPricesEur(eq("BTC"), any(), any()))
            .thenThrow(new IllegalStateException("a real bug"));
        when(coinGecko.getHistoricalPricesEur(eq("ETH"), any(), any()))
            .thenReturn(Map.of(from, new BigDecimal("3000")));
        when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());

        int saved = assertThatNoStartupFailure(() ->
            priceService.backfillHistoricalPrices(new java.util.LinkedHashSet<>(List.of("BTC", "ETH")), from));

        assertThat(saved).isEqualTo(1);
        verify(priceSnapshotRepository).save(any());

        assertThat(eventsAt(Level.ERROR)).hasSize(2);
        assertThat(eventsAt(Level.ERROR).get(0).getFormattedMessage()).contains("BTC");
        assertThat(eventsAt(Level.WARN)).isEmpty();
        assertThat(eventsAt(Level.ERROR).get(1).getFormattedMessage())
            .contains("1 of 2 tickers failing");
    }

    @Test
    void backfill_routesToYahoo_forTickersCoinGeckoDoesNotSupport() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        when(coinGecko.supports("AAPL")).thenReturn(false);
        when(yahoo.getHistoricalPricesEur(eq("AAPL"), any(), any()))
            .thenReturn(Map.of(from, new BigDecimal("200")));
        when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());

        assertThat(priceService.backfillHistoricalPrices(Set.of("AAPL"), from)).isEqualTo(1);
    }

    private int assertThatNoStartupFailure(java.util.function.Supplier<Integer> backfill) {
        var result = new int[1];
        assertThatCode(() -> result[0] = backfill.get())
            .as("backfill must never propagate; PriceBackfillRunner would fail Spring Boot startup")
            .doesNotThrowAnyException();
        return result[0];
    }
}
