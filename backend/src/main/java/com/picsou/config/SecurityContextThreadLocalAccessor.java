package com.picsou.config;

import io.micrometer.context.ThreadLocalAccessor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Bridges Spring Security's thread-bound {@link SecurityContextHolder} into Micrometer/Reactor
 * context propagation.
 *
 * <p><b>Why this exists.</b> The embedded MCP server (Spring AI, WebMvc+SSE) executes {@code @Tool}
 * methods on a Reactor scheduler thread, <em>not</em> the servlet thread that {@code AccessKeyAuthFilter}
 * authenticated. Because {@link SecurityContextHolder} defaults to a plain {@code ThreadLocal}, the
 * tool thread sees no {@code Authentication}, so {@link com.picsou.mcp.ScopeEnforcementAspect} (and
 * {@code UserContext}) would find no scopes — every scoped tool call failed with "missing scope".
 *
 * <p>Registered with the {@link io.micrometer.context.ContextRegistry} and activated via
 * {@code Hooks.enableAutomaticContextPropagation()} (see {@link McpSecurityContextPropagationConfig}),
 * this accessor lets Reactor capture the {@link SecurityContext} at subscription time and restore it
 * around tool execution, so the authority-based scope check works across the thread hop.
 *
 * <p><b>Null-safety.</b> Reactor calls {@link #restore(SecurityContext)} with the value the target
 * thread held <em>before</em> the snapshot was applied; that prior value is {@code null} for a fresh
 * scheduler thread. {@code SecurityContextHolder.setContext(null)} throws, so every setter clears the
 * holder instead of forwarding a {@code null}.
 */
public class SecurityContextThreadLocalAccessor implements ThreadLocalAccessor<SecurityContext> {

    /** Stable key under which the security context travels in a Reactor context snapshot. */
    public static final String KEY = "com.picsou.security.context";

    @Override
    public Object key() {
        return KEY;
    }

    /** Only a populated context is worth propagating — an empty holder returns {@code null} (skipped). */
    @Override
    public SecurityContext getValue() {
        SecurityContext context = SecurityContextHolder.getContext();
        return context.getAuthentication() != null ? context : null;
    }

    @Override
    public void setValue(SecurityContext value) {
        if (value == null) {
            SecurityContextHolder.clearContext();
        } else {
            SecurityContextHolder.setContext(value);
        }
    }

    /** Reactor 1.0-era no-arg reset: clear the holder on this thread. */
    @Override
    public void setValue() {
        SecurityContextHolder.clearContext();
    }

    @Override
    public void restore(SecurityContext previousValue) {
        setValue(previousValue);
    }

    @Override
    public void restore() {
        SecurityContextHolder.clearContext();
    }
}
