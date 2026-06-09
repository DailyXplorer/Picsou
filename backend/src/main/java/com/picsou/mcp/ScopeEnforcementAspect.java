package com.picsou.mcp;

import com.picsou.exception.MissingScopeException;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Enforces {@link RequiresScope} on MCP tool methods. Runs {@code @Before} the annotated method and
 * throws {@link MissingScopeException} unless the current {@link Authentication} carries the required
 * scope as a granted authority.
 *
 * <p>The check is authority-based, not type-based: a key request carries scope authorities, while a
 * cookie principal carries only {@code ROLE_*} — so a cookie can never satisfy a scope, giving the
 * MCP surface defense in depth on top of {@code AccessKeyAuthFilter}.
 */
@Aspect
@Component
public class ScopeEnforcementAspect {

    @Before("@annotation(requiresScope)")
    public void enforce(RequiresScope requiresScope) {
        String required = requiresScope.value();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean granted = auth != null && auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(required::equals);
        if (!granted) {
            throw new MissingScopeException(required);
        }
    }
}
