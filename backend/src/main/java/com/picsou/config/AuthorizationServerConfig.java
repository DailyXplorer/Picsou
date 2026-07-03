package com.picsou.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.picsou.model.AppUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * OAuth2 Authorization Server for the native iOS app (Authorization Code + PKCE).
 *
 * <p>This is a second, higher-priority {@link SecurityFilterChain} scoped to the authorization
 * server endpoints ({@code /oauth2/**}); the existing stateless API chain in
 * {@link SecurityConfig} is unchanged (now {@code @Order(2)}). The web cookie flow keeps working
 * exactly as before.
 *
 * <p>Two deliberate design choices tie this into the existing stack rather than bolting on a
 * parallel identity system:
 * <ul>
 *   <li><b>Same HS256 secret.</b> Tokens are signed with a symmetric {@link OctetSequenceKey}
 *       derived from {@code JWT_SECRET}, and {@link #jwtTokenCustomizer()} reproduces the exact
 *       claims the existing {@link JwtTokenAuthenticator} expects ({@code type=access}, {@code uid},
 *       {@code tv}, {@code sub}, {@code role}). The resource-server validation logic is unchanged.</li>
 *   <li><b>Reuse the web login.</b> {@link CookieBridgeAuthenticationFilter} authenticates the
 *       authorize request from the existing {@code access_token} cookie; when it is absent the
 *       {@link #spaLoginRedirectEntryPoint()} bounces the in-app browser to the SPA login
 *       ({@code /login?returnTo=…}), which runs the untouched password + TOTP + Remember-Me flow.</li>
 * </ul>
 *
 * @see <a href="file:../../../../../../docs/decisions/2026-07-03-oauth2-authorization-server-for-native-app.md">ADR</a>
 */
@Configuration
public class AuthorizationServerConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(
        HttpSecurity http,
        JwtTokenAuthenticator jwtTokenAuthenticator
    ) throws Exception {

        OAuth2AuthorizationServerConfigurer authorizationServer =
            OAuth2AuthorizationServerConfigurer.authorizationServer();

        http
            .securityMatcher(authorizationServer.getEndpointsMatcher())
            .with(authorizationServer, Customizer.withDefaults())
            .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
            // The token endpoint is called by the native app with PKCE (no browser session);
            // the authorize endpoint is a GET. CSRF protection is not applicable to this chain.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            // Populate the SecurityContext from the existing access_token cookie so the authorize
            // endpoint knows who is authorizing. Anchored on UsernamePasswordAuthenticationFilter
            // (a well-known order slot) to run ahead of the authorization + AS endpoint filters.
            .addFilterBefore(new CookieBridgeAuthenticationFilter(jwtTokenAuthenticator),
                UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex.authenticationEntryPoint(spaLoginRedirectEntryPoint()));

        return http.build();
    }

    /**
     * The single first-party public client. PKCE is mandatory; there is no client secret.
     * Consent is skipped (the app and server are operated by the same person).
     */
    @Bean
    public RegisteredClientRepository registeredClientRepository(OAuthClientProperties props) {
        RegisteredClient iosClient = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(props.getClientId())
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUri(props.getRedirectUri())
            .scope("read")
            .scope("write")
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)               // PKCE (S256) required
                .requireAuthorizationConsent(false)  // first-party → no consent screen
                .build())
            .tokenSettings(TokenSettings.builder()
                .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)   // JWT
                .accessTokenTimeToLive(Duration.ofMinutes(props.getAccessTokenTtlMinutes()))
                .refreshTokenTimeToLive(Duration.ofDays(props.getRefreshTokenTtlDays()))
                .reuseRefreshTokens(false)            // rotate refresh tokens on each use
                .build())
            .build();
        return new InMemoryRegisteredClientRepository(iosClient);
    }

    /**
     * Symmetric HS256 signing key built from the same {@code JWT_SECRET} used by {@link JwtUtil},
     * so tokens minted here validate through the existing resource-server path. The key has no
     * public half, so the {@code /oauth2/jwks} endpoint exposes nothing.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource(@Value("${app.jwt.secret}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        OctetSequenceKey key = new OctetSequenceKey.Builder(keyBytes)
            .keyID("picsou-hs256")
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(com.nimbusds.jose.JWSAlgorithm.HS256)
            .build();
        return new ImmutableJWKSet<>(new JWKSet(key));
    }

    /**
     * Force HMAC signing and stamp the claims the existing filter requires. The principal is the
     * {@link AppUser} set by the cookie bridge (or, on refresh, the snapshot captured at
     * authorization time). Reading {@code tv} from that snapshot — not from a fresh DB load — is
     * intentional: a later password change bumps {@code AppUser.tokenVersion}, so refreshed tokens
     * still carry the old {@code tv} and are rejected by the API, logging the device out.
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getJwsHeader().algorithm(MacAlgorithm.HS256);
                if (context.getPrincipal().getPrincipal() instanceof AppUser user) {
                    context.getClaims()
                        .subject(user.getUsername())
                        .claim("type", "access")
                        .claim("uid", user.getId())
                        .claim("tv", user.getTokenVersion())
                        .claim("role", user.getRole().name());
                }
            }
        };
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
    }

    /**
     * Redirect unauthenticated authorize requests to the existing SPA login, carrying the original
     * authorize URL so the SPA can bounce back after login. Relative path → resolves against the
     * user's own instance host (works behind nginx). The SPA validates {@code returnTo} is a
     * same-origin path before navigating (open-redirect guard).
     */
    private AuthenticationEntryPoint spaLoginRedirectEntryPoint() {
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) -> {
            String target = request.getRequestURI();
            if (request.getQueryString() != null) {
                target = target + "?" + request.getQueryString();
            }
            String returnTo = URLEncoder.encode(target, StandardCharsets.UTF_8);
            response.sendRedirect("/login?returnTo=" + returnTo);
        };
    }
}
