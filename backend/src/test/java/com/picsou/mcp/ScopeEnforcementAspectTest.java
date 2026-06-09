package com.picsou.mcp;

import com.picsou.exception.MissingScopeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScopeEnforcementAspectTest {

    private final ScopeEnforcementAspect aspect = new ScopeEnforcementAspect();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** Annotations are interfaces — Mockito can stub value() to drive the advice directly. */
    private RequiresScope requiring(String scope) {
        RequiresScope ann = mock(RequiresScope.class);
        when(ann.value()).thenReturn(scope);
        return ann;
    }

    private void grant(String... authorities) {
        var granted = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("key", null, granted));
    }

    @Test
    void allows_whenRequiredScopeGranted() {
        grant("goals:read", "goals:write");
        assertThatCode(() -> aspect.enforce(requiring("goals:write"))).doesNotThrowAnyException();
    }

    @Test
    void denies_whenRequiredScopeMissing() {
        grant("goals:read");
        assertThatThrownBy(() -> aspect.enforce(requiring("goals:write")))
            .isInstanceOf(MissingScopeException.class)
            .hasMessageContaining("goals:write");
    }

    @Test
    void denies_whenNotAuthenticated() {
        assertThatThrownBy(() -> aspect.enforce(requiring("goals:read")))
            .isInstanceOf(MissingScopeException.class);
    }

    @Test
    void denies_whenPrincipalHasOnlyARole() {
        // A cookie principal carries ROLE_MEMBER / ROLE_ADMIN but no scope authorities,
        // so it can never satisfy @RequiresScope — defense in depth on the MCP surface.
        grant("ROLE_ADMIN");
        assertThatThrownBy(() -> aspect.enforce(requiring("accounts:read")))
            .isInstanceOf(MissingScopeException.class);
    }
}
