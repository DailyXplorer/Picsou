package com.picsou.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit coverage for the Reactor↔Spring-Security bridge that survives the MCP servlet→tool thread hop.
 *
 * <p>The accessor must (a) only export a populated context, (b) re-install a captured context on the
 * target thread, and (c) <em>self-clear</em> on every reset/close path so a pooled scheduler thread
 * never leaks one request's {@code Authentication} into the next. These are the exact behaviours that
 * single-thread unit tests on the aspect could not catch, so they are pinned here directly.
 */
class SecurityContextThreadLocalAccessorTest {

    private final SecurityContextThreadLocalAccessor accessor = new SecurityContextThreadLocalAccessor();

    @AfterEach
    void clearHolder() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void key_isStable() {
        assertThat(accessor.key()).isEqualTo(SecurityContextThreadLocalAccessor.KEY);
    }

    @Test
    void getValue_emptyHolder_returnsNull() {
        SecurityContextHolder.clearContext();
        assertThat(accessor.getValue()).isNull();
    }

    @Test
    void getValue_populatedHolder_returnsContext() {
        SecurityContext context = newContextWith(authentication());
        SecurityContextHolder.setContext(context);

        assertThat(accessor.getValue()).isSameAs(context);
    }

    @Test
    void setValue_nonNull_installsContext() {
        SecurityContext context = newContextWith(authentication());

        accessor.setValue(context);

        assertThat(SecurityContextHolder.getContext()).isSameAs(context);
    }

    @Test
    void setValue_null_clearsHolderWithoutThrowing() {
        SecurityContextHolder.setContext(newContextWith(authentication()));

        // SecurityContextHolder.setContext(null) throws — the accessor must clear instead.
        assertThatCode(() -> accessor.setValue(null)).doesNotThrowAnyException();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void setValue_noArg_clearsHolder() {
        SecurityContextHolder.setContext(newContextWith(authentication()));

        accessor.setValue();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void restore_previousValue_reinstallsIt() {
        SecurityContext previous = newContextWith(authentication());

        accessor.restore(previous);

        assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
    }

    @Test
    void restore_nullPrevious_clearsHolderWithoutThrowing() {
        // Fresh scheduler thread held nothing before the snapshot — Reactor restores null.
        SecurityContextHolder.setContext(newContextWith(authentication()));

        assertThatCode(() -> accessor.restore(null)).doesNotThrowAnyException();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void restore_noArg_clearsHolder() {
        SecurityContextHolder.setContext(newContextWith(authentication()));

        accessor.restore();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void fullCycle_setThenRestoreNull_leavesNoBleed() {
        // Mimic the tool thread: install the captured context, run, then close → must end clean
        // so the next request scheduled onto the same pooled thread starts with an empty holder.
        SecurityContext captured = newContextWith(authentication());

        accessor.setValue(captured);
        assertThat(SecurityContextHolder.getContext()).isSameAs(captured);

        accessor.restore(null);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static Authentication authentication() {
        return new UsernamePasswordAuthenticationToken("psk-owner", "n/a", java.util.List.of());
    }

    private static SecurityContext newContextWith(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        return context;
    }
}
