package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Exercises the single shared access-token validation path used by the cookie, the Bearer header
 * and the OAuth2 cookie bridge. Uses a real {@link JwtUtil} to mint tokens (so signature and claim
 * shape are genuine) and a mocked repository for the user lookup.
 */
@ExtendWith(MockitoExtension.class)
class JwtTokenAuthenticatorTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-test";

    @Mock AppUserRepository userRepository;

    JwtUtil jwtUtil;
    JwtTokenAuthenticator authenticator;
    AppUser user;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 15, 7, 5);
        authenticator = new JwtTokenAuthenticator(jwtUtil, userRepository);
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
    void validAccessToken_forActiveUserWithMatchingTokenVersion_authenticates() {
        when(userRepository.findByIdWithMember(42L)).thenReturn(Optional.of(user));
        String token = jwtUtil.generateAccessToken(user);

        Optional<Authentication> result = authenticator.authenticate(token);

        assertThat(result).isPresent();
        assertThat(result.get().getPrincipal()).isSameAs(user);
        assertThat(result.get().getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    void refreshToken_isRejected() {
        // A refresh token is validly signed but must never authenticate a request.
        lenient().when(userRepository.findByIdWithMember(42L)).thenReturn(Optional.of(user));
        String refresh = jwtUtil.generateRefreshToken(user);

        assertThat(authenticator.authenticate(refresh)).isEmpty();
    }

    @Test
    void tokenVersionMismatch_isRejected() {
        // Token minted at tv=3, then the user's tokenVersion is bumped (e.g. password change).
        String token = jwtUtil.generateAccessToken(user);
        user.setTokenVersion(4L);
        when(userRepository.findByIdWithMember(42L)).thenReturn(Optional.of(user));

        assertThat(authenticator.authenticate(token)).isEmpty();
    }

    @Test
    void deactivatedUser_isRejected() {
        String token = jwtUtil.generateAccessToken(user);
        user.setActivated(false);
        when(userRepository.findByIdWithMember(42L)).thenReturn(Optional.of(user));

        assertThat(authenticator.authenticate(token)).isEmpty();
    }

    @Test
    void unknownUser_isRejected() {
        String token = jwtUtil.generateAccessToken(user);
        when(userRepository.findByIdWithMember(42L)).thenReturn(Optional.empty());

        assertThat(authenticator.authenticate(token)).isEmpty();
    }

    @Test
    void forgedToken_isRejected() {
        // Signed with a different secret → signature verification fails.
        JwtUtil attacker = new JwtUtil("ffffffffffffffffffffffffffffffff-evil", 15, 7, 5);
        String forged = attacker.generateAccessToken(user);

        assertThat(authenticator.authenticate(forged)).isEmpty();
    }

    @Test
    void nullOrBlankToken_isRejected() {
        assertThat(authenticator.authenticate(null)).isEmpty();
        assertThat(authenticator.authenticate("   ")).isEmpty();
    }
}
