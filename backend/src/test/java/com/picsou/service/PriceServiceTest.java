package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.YahooFinancePriceProvider;
import com.picsou.repository.PriceSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
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

        // Second call within the TTL: served from cache, no second upstream fetch.
        Map<String, BigDecimal> second = priceService.refreshPrices(Set.of("AAPL"));
        assertThat(second).containsEntry("AAPL", new BigDecimal("150"));

        verify(yahoo, times(1)).getPricesEur(anySet());
        // Snapshots are persisted only for freshly fetched prices.
        verify(priceSnapshotRepository, times(1)).save(any());
    }

    @Test
    void refreshPrices_fetchesOnlyMissingTickers() {
        lenient().when(coinGecko.supports(any())).thenReturn(false);
        when(yahoo.getPricesEur(Set.of("AAPL"))).thenReturn(Map.of("AAPL", new BigDecimal("150")));
        lenient().when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());
        priceService.refreshPrices(Set.of("AAPL")); // warm the cache

        when(yahoo.getPricesEur(Set.of("MSFT"))).thenReturn(Map.of("MSFT", new BigDecimal("410")));

        Map<String, BigDecimal> result = priceService.refreshPrices(Set.of("AAPL", "MSFT", "EUR"));

        assertThat(result)
            .containsEntry("AAPL", new BigDecimal("150"))
            .containsEntry("MSFT", new BigDecimal("410"))
            .containsEntry("EUR", BigDecimal.ONE);
        // AAPL came from cache: yahoo was asked only for the missing ticker.
        verify(yahoo).getPricesEur(Set.of("MSFT"));
    }
}
