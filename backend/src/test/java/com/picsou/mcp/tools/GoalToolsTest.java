package com.picsou.mcp.tools;

import com.picsou.dto.GoalMonthEntryResponse;
import com.picsou.dto.GoalProgressResponse;
import com.picsou.dto.GoalRequest;
import com.picsou.model.FamilyMember;
import com.picsou.service.GoalService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every goal tool must resolve {@link UserContext#currentMemberId()} (or {@code currentMember()})
 * and delegate to the already member-scoped {@link GoalService}. These tests pin that delegation;
 * goal math and member isolation are owned and tested by the service.
 */
@ExtendWith(MockitoExtension.class)
class GoalToolsTest {

    private static final long MID = 7L;

    @Mock GoalService goalService;
    @Mock UserContext userContext;
    @InjectMocks GoalTools tools;

    @Test
    void listGoals_delegatesScopedToCurrentMember() {
        GoalProgressResponse g = mock(GoalProgressResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(goalService.findAll(MID)).thenReturn(List.of(g));

        assertThat(tools.listGoals()).containsExactly(g);
    }

    @Test
    void getGoal_delegatesScopedToCurrentMember() {
        GoalProgressResponse g = mock(GoalProgressResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(goalService.findById(5L, MID)).thenReturn(g);

        assertThat(tools.getGoal(5L)).isSameAs(g);
    }

    @Test
    void getGoalMonthlyEntries_delegatesScopedToCurrentMember() {
        GoalMonthEntryResponse e = mock(GoalMonthEntryResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(goalService.getMonthlyEntries(5L, MID)).thenReturn(List.of(e));

        assertThat(tools.getGoalMonthlyEntries(5L)).containsExactly(e);
    }

    @Test
    void createGoal_buildsRequestAndDelegatesWithCurrentMember() {
        FamilyMember member = FamilyMember.builder().id(MID).build();
        GoalProgressResponse created = mock(GoalProgressResponse.class);
        LocalDate deadline = LocalDate.of(2027, 1, 1);
        when(userContext.currentMember()).thenReturn(member);
        when(goalService.create(any(GoalRequest.class), eq(member))).thenReturn(created);

        GoalProgressResponse out = tools.createGoal(
            "House", new BigDecimal("50000"), deadline, List.of(1L, 2L));

        assertThat(out).isSameAs(created);
        ArgumentCaptor<GoalRequest> captor = ArgumentCaptor.forClass(GoalRequest.class);
        verify(goalService).create(captor.capture(), eq(member));
        GoalRequest req = captor.getValue();
        assertThat(req.name()).isEqualTo("House");
        assertThat(req.targetAmount()).isEqualByComparingTo("50000");
        assertThat(req.deadline()).isEqualTo(deadline);
        assertThat(req.accountIds()).containsExactly(1L, 2L);
    }

    @Test
    void updateGoal_buildsRequestAndDelegatesScopedToCurrentMember() {
        GoalProgressResponse updated = mock(GoalProgressResponse.class);
        LocalDate deadline = LocalDate.of(2028, 6, 1);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(goalService.update(eq(5L), any(GoalRequest.class), eq(MID))).thenReturn(updated);

        GoalProgressResponse out = tools.updateGoal(
            5L, "Bigger house", new BigDecimal("75000"), deadline, List.of(3L));

        assertThat(out).isSameAs(updated);
        ArgumentCaptor<GoalRequest> captor = ArgumentCaptor.forClass(GoalRequest.class);
        verify(goalService).update(eq(5L), captor.capture(), eq(MID));
        assertThat(captor.getValue().accountIds()).containsExactly(3L);
    }

    @Test
    void deleteGoal_delegatesScopedToCurrentMember() {
        when(userContext.currentMemberId()).thenReturn(MID);

        tools.deleteGoal(5L);

        verify(goalService).delete(5L, MID);
    }

    @Test
    void setGoalMonthContribution_delegatesScopedToCurrentMember() {
        GoalMonthEntryResponse entry = mock(GoalMonthEntryResponse.class);
        when(userContext.currentMemberId()).thenReturn(MID);
        when(goalService.setManualContribution(eq(5L), eq("2026-03"), any(BigDecimal.class), eq(MID)))
            .thenReturn(entry);

        GoalMonthEntryResponse out = tools.setGoalMonthContribution(5L, "2026-03", new BigDecimal("500"));

        assertThat(out).isSameAs(entry);
        verify(goalService).setManualContribution(5L, "2026-03", new BigDecimal("500"), MID);
    }
}
