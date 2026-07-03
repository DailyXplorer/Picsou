package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.repository.AppUserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Single source of truth for turning a raw <em>access</em> JWT into an authenticated
 * {@link Authentication}. Extracted so the three entry points that accept an access token —
 * the {@code access_token} cookie ({@link JwtAuthenticationFilter}), the
 * {@code Authorization: Bearer} header used by the native app ({@link JwtAuthenticationFilter}),
 * and the OAuth2 authorization-server cookie bridge ({@link CookieBridgeAuthenticationFilter}) —
 * share exactly one validation path. Security-sensitive checks (signature, token type,
 * {@code tv} token-version, activation) must never drift between callers.
 */
@Component
public class JwtTokenAuthenticator {

    private final JwtUtil jwtUtil;
    private final AppUserRepository userRepository;

    public JwtTokenAuthenticator(JwtUtil jwtUtil, AppUserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    /**
     * Validate {@code token} as an access JWT and, if it maps to an active user whose
     * {@code tv} claim still matches the persisted token version, return the corresponding
     * authentication carrying a single {@code ROLE_*} authority. Returns empty for any
     * failure (missing/invalid/expired token, wrong token type, revoked version, unknown or
     * deactivated user) — callers simply stay unauthenticated.
     */
    public Optional<Authentication> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = jwtUtil.validateAndParse(token);
            if (!jwtUtil.isAccessToken(claims)) {
                return Optional.empty();
            }
            Long userId = claims.get("uid", Long.class);
            Long tv = jwtUtil.getTokenVersion(claims);
            if (userId == null) {
                return Optional.empty();
            }
            AppUser user = userRepository.findByIdWithMember(userId).orElse(null);
            if (user != null && user.isActivated() && tv != null && tv == user.getTokenVersion()) {
                String role = "ROLE_" + user.getRole().name();
                return Optional.of(new UsernamePasswordAuthenticationToken(
                    user,
                    null,
                    List.of(new SimpleGrantedAuthority(role))
                ));
            }
        } catch (JwtException ex) {
            // Invalid/expired/forged token — treat as unauthenticated.
        }
        return Optional.empty();
    }
}
