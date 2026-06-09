package com.picsou.config;

import com.picsou.mcp.AccessKeyService;
import com.picsou.mcp.AccessKeyService.ResolvedKey;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates an MCP request that carries an {@code Authorization: Bearer psk_…} access-key.
 *
 * <p>This is the second authentication principal in the app, alongside the JWT cookie. Three
 * structural guarantees keep it confined to the curated MCP surface:
 * <ul>
 *   <li><b>Property A</b> — {@link #shouldNotFilter} returns {@code true} for any non-{@code /mcp}
 *       path, so a {@code psk_} token presented to {@code /api/**} is never even validated; it cannot
 *       set a {@link SecurityContextHolder}, so those endpoints answer 401.</li>
 *   <li><b>Property B</b> — the {@link AccessKeyAuthentication} type marks the request as key-driven,
 *       letting {@code UserContext} refuse the admin {@code ?memberId=} override.</li>
 *   <li><b>Property C</b> — authorities are scope strings only, never {@code ROLE_*}.</li>
 * </ul>
 *
 * <p>Runs last among the {@code UsernamePasswordAuthenticationFilter}-anchored filters. A per-key
 * Bucket4j throttle returns 429 {@code problem+json} on overflow.
 */
public class AccessKeyAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String KEY_PREFIX = "psk_";

    private final AccessKeyService accessKeyService;
    private final Map<Long, Bucket> keyBuckets;

    public AccessKeyAuthFilter(AccessKeyService accessKeyService, Map<Long, Bucket> keyBuckets) {
        this.accessKeyService = accessKeyService;
        this.keyBuckets = keyBuckets;
    }

    /** Property A: an access-key authenticates ONLY the MCP surface, never {@code /api/**}. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/mcp");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {

        String raw = extractBearerKey(request);
        if (raw == null) {
            // No key (or a non-psk bearer) — leave unauthenticated; /mcp then answers 401.
            chain.doFilter(request, response);
            return;
        }

        Optional<ResolvedKey> resolved = accessKeyService.validate(raw);
        if (resolved.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        ResolvedKey key = resolved.get();

        // Per-key throttle: created lazily on first use, shared across this key's requests.
        Bucket bucket = keyBuckets.computeIfAbsent(key.keyId(), id -> RateLimitConfig.createMcpKeyBucket());
        if (!bucket.tryConsume(1)) {
            writeTooManyRequests(response);
            return;
        }

        var authorities = key.scopes().stream()
            .map(SimpleGrantedAuthority::new)
            .toList();
        var authentication = new AccessKeyAuthentication(key.owner(), authorities, key.keyId());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(request, response);
    }

    /** Pull a {@code psk_} secret out of the Bearer header, or {@code null} if absent/foreign. */
    private String extractBearerKey(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.startsWith(KEY_PREFIX) ? token : null;
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/problem+json");
        response.getWriter().write("""
            {"status":429,"title":"Too Many Requests","detail":"Rate limit exceeded for this access key"}
            """);
    }
}
