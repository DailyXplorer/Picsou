package com.picsou.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an MCP {@code @Tool} method as requiring a specific access-key scope (e.g.
 * {@code @RequiresScope(Scopes.GOALS_WRITE)}). Enforced by {@code ScopeEnforcementAspect}, which
 * denies the call with {@link com.picsou.exception.MissingScopeException} unless the scope is among
 * the authenticated key's authorities. Must annotate {@code public} methods on Spring beans so the
 * AOP proxy can intercept them.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresScope {
    String value();
}
