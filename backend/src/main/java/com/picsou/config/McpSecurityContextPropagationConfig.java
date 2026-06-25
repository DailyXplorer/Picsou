package com.picsou.config;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Makes Spring Security's {@link org.springframework.security.core.context.SecurityContext} survive the
 * servlet→Reactor thread hop that Spring AI's MCP server performs when invoking {@code @Tool} methods.
 *
 * <p>Registers {@link SecurityContextThreadLocalAccessor} with the global {@link ContextRegistry} and
 * turns on Reactor's automatic context propagation. With both in place, the {@code Authentication} that
 * {@code AccessKeyAuthFilter} set on the request thread is captured into the Reactor context at
 * subscription and re-installed on the scheduler thread that runs the tool, so
 * {@link com.picsou.mcp.ScopeEnforcementAspect} and {@code UserContext} resolve scopes and member as
 * intended.
 *
 * <p>Gated on the MCP server being enabled so a deployment with {@code MCP_ENABLED=false} keeps the
 * default (no global Reactor hook). The hook is process-global and idempotent.
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
public class McpSecurityContextPropagationConfig {

    private static final Logger log = LoggerFactory.getLogger(McpSecurityContextPropagationConfig.class);

    @PostConstruct
    void enableSecurityContextPropagation() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new SecurityContextThreadLocalAccessor());
        Hooks.enableAutomaticContextPropagation();
        log.info("MCP: enabled Reactor automatic context propagation for the security context");
    }
}
