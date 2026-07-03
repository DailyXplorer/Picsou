package com.picsou.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The crux of the design: a token minted by the OAuth2 authorization server (HS256 + custom claims)
 * must validate through the <em>existing, unchanged</em> resource-server path ({@link JwtUtil} /
 * {@link JwtTokenAuthenticator}). This test runs the real {@link AuthorizationServerConfig#jwkSource}
 * and {@link AuthorizationServerConfig#jwtTokenCustomizer()} against a {@link JwtEncodingContext},
 * signs the JWT with the same {@link NimbusJwtEncoder} the server uses at runtime, and feeds the
 * result to the resource server.
 */
class AuthorizationServerConfigTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-test";

    AuthorizationServerConfig config;
    AppUser user;

    @BeforeEach
    void setUp() {
        config = new AuthorizationServerConfig();
        user = AppUser.builder()
            .id(42L)
            .username("alice")
            .passwordHash("h")
            .role(UserRole.ADMIN)
            .activated(true)
            .tokenVersion(3L)
            .build();
    }

    @Test
    void customizerStampsResourceServerClaimsAndForcesHs256() {
        JwtEncodingContext context = accessTokenContext(user);

        config.jwtTokenCustomizer().customize(context);

        JwsHeader header = context.getJwsHeader().build();
        assertThat(header.getAlgorithm()).isEqualTo(MacAlgorithm.HS256);

        JwtClaimsSet claims = context.getClaims().build();
        String type = claims.getClaim("type");
        Long uid = claims.getClaim("uid");
        Long tv = claims.getClaim("tv");
        String role = claims.getClaim("role");
        assertThat(type).isEqualTo("access");
        assertThat(uid).isEqualTo(42L);
        assertThat(tv).isEqualTo(3L);
        assertThat(role).isEqualTo("ADMIN");
        assertThat(claims.getSubject()).isEqualTo("alice");
    }

    @Test
    void mintedToken_isAcceptedByTheExistingResourceServer() {
        JwtEncodingContext context = accessTokenContext(user);
        config.jwtTokenCustomizer().customize(context);

        String tokenValue = sign(context);

        // 1) The raw jjwt validation the API filter uses.
        JwtUtil jwtUtil = new JwtUtil(SECRET, 15, 7, 5);
        Claims parsed = jwtUtil.validateAndParse(tokenValue);
        assertThat(jwtUtil.isAccessToken(parsed)).isTrue();
        assertThat(parsed.get("uid", Long.class)).isEqualTo(42L);
        assertThat(jwtUtil.getTokenVersion(parsed)).isEqualTo(3L);
        assertThat(parsed.get("role", String.class)).isEqualTo("ADMIN");
        assertThat(parsed.getSubject()).isEqualTo("alice");

        // 2) End-to-end through the shared authenticator (with the user present + tv matching).
        AppUserRepository repo = mock(AppUserRepository.class);
        when(repo.findByIdWithMember(42L)).thenReturn(java.util.Optional.of(user));
        JwtTokenAuthenticator authenticator = new JwtTokenAuthenticator(jwtUtil, repo);

        assertThat(authenticator.authenticate(tokenValue)).isPresent();
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private JwtEncodingContext accessTokenContext(AppUser principalUser) {
        RegisteredClient client = RegisteredClient.withId("test")
            .clientId("picsou-ios")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("picsou://callback")
            .scope("read")
            .build();

        Authentication principal = new UsernamePasswordAuthenticationToken(
            principalUser, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        JwsHeader.Builder headers = JwsHeader.with(SignatureAlgorithm.RS256); // default; customizer overrides
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuer("https://picsou.local")
            .subject("placeholder")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(900));

        return JwtEncodingContext.with(headers, claims)
            .registeredClient(client)
            .principal(principal)
            .tokenType(OAuth2TokenType.ACCESS_TOKEN)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .build();
    }

    private String sign(JwtEncodingContext context) {
        JWKSource<SecurityContext> jwkSource = config.jwkSource(SECRET);
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(jwkSource);
        Jwt jwt = encoder.encode(JwtEncoderParameters.from(context.getJwsHeader().build(), context.getClaims().build()));
        return jwt.getTokenValue();
    }
}
