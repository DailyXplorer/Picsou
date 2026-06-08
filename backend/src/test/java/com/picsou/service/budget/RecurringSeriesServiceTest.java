package com.picsou.service.budget;

import com.picsou.dto.RecurringActivityResponse;
import com.picsou.dto.RecurringActivityType;
import com.picsou.dto.RecurringRuntimeStatus;
import com.picsou.dto.RecurringSeriesResponse;
import com.picsou.model.RecurringCadence;
import com.picsou.model.RecurringSeries;
import com.picsou.model.RecurringStatus;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RecurringSeriesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the lifecycle surface over recurring series: the computed runtime status on the
 * list, the derived "what changed" activity feed, and the context-aware undo. Detection itself is
 * covered by {@link RecurringDetectionServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class RecurringSeriesServiceTest {

    @Mock RecurringSeriesRepository seriesRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock FamilyMemberRepository familyMemberRepository;

    @InjectMocks RecurringSeriesService service;

    private static final Long MEMBER_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 9);

    private static RecurringSeries.RecurringSeriesBuilder series() {
        return RecurringSeries.builder()
            .id(1L)
            .label("Netflix")
            .expectedAmount(new BigDecimal("-12.99"))
            .cadence(RecurringCadence.MONTHLY)
            .status(RecurringStatus.CONFIRMED);
    }

    private void echoSave() {
        when(seriesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─── Runtime status on the list ─────────────────────────────────────────────

    @Test
    void findAll_computesRuntimeStatusRelativeToToday() {
        RecurringSeries late = series().id(1L).label("Late").nextDueDate(TODAY.minusDays(1)).build();
        RecurringSeries dueSoon = series().id(2L).label("Soon").nextDueDate(TODAY.plusDays(3)).build();
        RecurringSeries scheduled = series().id(3L).label("Later").nextDueDate(TODAY.plusDays(30)).build();
        when(seriesRepository.findAllByMemberIdOrderByNextDueDateAsc(MEMBER_ID))
            .thenReturn(List.of(late, dueSoon, scheduled));

        List<RecurringSeriesResponse> result = service.findAll(MEMBER_ID, null, TODAY);

        assertThat(result).extracting(RecurringSeriesResponse::runtimeStatus)
            .containsExactly(
                RecurringRuntimeStatus.LATE,
                RecurringRuntimeStatus.DUE_SOON,
                RecurringRuntimeStatus.SCHEDULED);
    }

    // ─── Activity feed ──────────────────────────────────────────────────────────

    @Test
    void activity_surfacesRecentAutoConfirmAndPriceChangeNewestFirst() {
        RecurringSeries autoConfirmed = series().id(1L).label("Spotify")
            .autoConfirmed(true).status(RecurringStatus.CONFIRMED)
            .lastSeenDate(TODAY.minusDays(20)).build();
        RecurringSeries repriced = series().id(2L).label("Netflix")
            .expectedAmount(new BigDecimal("-13.99"))
            .previousAmount(new BigDecimal("-12.99"))
            .priceChangedAt(TODAY.minusDays(2)).build();
        when(seriesRepository.findAllByMemberIdOrderByNextDueDateAsc(MEMBER_ID))
            .thenReturn(List.of(autoConfirmed, repriced));

        List<RecurringActivityResponse> feed = service.activity(MEMBER_ID, TODAY);

        assertThat(feed).hasSize(2);
        // Newest first: the 2-day-old price change precedes the 20-day-old auto-confirm.
        assertThat(feed.get(0).type()).isEqualTo(RecurringActivityType.PRICE_CHANGE);
        assertThat(feed.get(0).previousAmount()).isEqualByComparingTo("-12.99");
        assertThat(feed.get(0).expectedAmount()).isEqualByComparingTo("-13.99");
        assertThat(feed.get(1).type()).isEqualTo(RecurringActivityType.AUTO_CONFIRMED);
        assertThat(feed.get(1).previousAmount()).isNull();
    }

    @Test
    void activity_excludesStaleAndUserConfirmedSeries() {
        RecurringSeries stale = series().id(1L).label("Old")
            .autoConfirmed(true).lastSeenDate(TODAY.minusDays(90)).build(); // beyond the 60d window
        RecurringSeries userConfirmed = series().id(2L).label("Manual")
            .autoConfirmed(false).lastSeenDate(TODAY.minusDays(5)).build(); // confirmed by the user
        when(seriesRepository.findAllByMemberIdOrderByNextDueDateAsc(MEMBER_ID))
            .thenReturn(List.of(stale, userConfirmed));

        assertThat(service.activity(MEMBER_ID, TODAY)).isEmpty();
    }

    @Test
    void activity_emitsSingleEntryPreferringPriceChange() {
        // A series both silently auto-confirmed AND recently re-priced → one entry, the price change.
        RecurringSeries both = series().id(1L).label("Disney+")
            .autoConfirmed(true).status(RecurringStatus.CONFIRMED)
            .lastSeenDate(TODAY.minusDays(3))
            .previousAmount(new BigDecimal("-8.99")).priceChangedAt(TODAY.minusDays(3)).build();
        when(seriesRepository.findAllByMemberIdOrderByNextDueDateAsc(MEMBER_ID))
            .thenReturn(List.of(both));

        List<RecurringActivityResponse> feed = service.activity(MEMBER_ID, TODAY);

        assertThat(feed).hasSize(1);
        assertThat(feed.get(0).type()).isEqualTo(RecurringActivityType.PRICE_CHANGE);
    }

    // ─── Undo ───────────────────────────────────────────────────────────────────

    @Test
    void undo_acknowledgesPriceChangeKeepingNewAmount() {
        RecurringSeries repriced = series().id(1L)
            .expectedAmount(new BigDecimal("-13.99"))
            .previousAmount(new BigDecimal("-12.99"))
            .priceChangedAt(TODAY.minusDays(2))
            .status(RecurringStatus.CONFIRMED).build();
        when(seriesRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(repriced));
        echoSave();

        service.undo(1L, MEMBER_ID, TODAY);

        assertThat(repriced.getExpectedAmount()).isEqualByComparingTo("-13.99"); // new amount kept
        assertThat(repriced.getPreviousAmount()).isNull();                       // alert cleared
        assertThat(repriced.getPriceChangedAt()).isNull();
        assertThat(repriced.getStatus()).isEqualTo(RecurringStatus.CONFIRMED);   // still a subscription
    }

    @Test
    void undo_rejectsSilentAutoConfirmByIgnoringSeries() {
        RecurringSeries autoConfirmed = series().id(1L)
            .autoConfirmed(true).status(RecurringStatus.CONFIRMED)
            .lastSeenDate(TODAY.minusDays(4)).build();
        when(seriesRepository.findByIdAndMemberId(1L, MEMBER_ID)).thenReturn(Optional.of(autoConfirmed));
        echoSave();

        service.undo(1L, MEMBER_ID, TODAY);

        assertThat(autoConfirmed.getStatus()).isEqualTo(RecurringStatus.IGNORED); // won't be re-confirmed
        assertThat(autoConfirmed.isAutoConfirmed()).isFalse();
    }
}
