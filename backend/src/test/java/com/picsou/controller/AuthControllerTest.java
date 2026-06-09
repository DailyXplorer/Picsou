package com.picsou.controller;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtUtil;
import com.picsou.dto.LoginRequest;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import com.picsou.service.SetupAuditService;
import io.github.bucket4j.Bucket;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import jakarta.servlet.http.Cookie;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock AppUserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtUtil jwtUtil;
    @Mock AuthCookieWriter cookieWriter;
    @Mock MfaService mfaService;
    @Mock PersistentSessionService persistentSessionService;
    @Mock SetupAuditService auditService;

    Map<String, Bucket> loginBuckets;
    Map<String, Bucket> mfaVerifyBuckets;
    AuthController controller;
    MockHttpServletRequest httpReq;
    MockHttpServletResponse httpRes;

    @BeforeEach
    void setUp() {
        loginBuckets = new HashMap<>();
        mfaVerifyBuckets = new HashMap<>();
        controller = newController(false);
        httpReq = new MockHttpServletRequest();
        httpReq.setRemoteAddr("10.0.0.5");
        httpRes = new MockHttpServletResponse();
    }

    private AuthController newController(boolean adminRecoveryEnabled) {
        return new AuthController(
            userRepository, passwordEncoder, jwtUtil,
            loginBuckets, mfaVerifyBuckets, cookieWriter,
            mfaService, persistentSessionService, auditService,
            adminRecoveryEnabled
        );
    }

    private AppUser user(boolean activated) {
        FamilyMember member = FamilyMember.builder()
            .id(42L).displayName("Alice").build();
        return AppUser.builder()
            .id(7L).username("alice")
            .role(UserRole.ADMIN)
            .passwordHash("$2a$12$hash")
            .activated(activated)
            .tokenVersion(3L)
            .member(member)
            .build();
    }

    // ─── login ───────────────────────────────────────────────────────────

    @Test
    void login_returns403_andSetsNoCookies_whenAccountNotActivated() {
        AppUser deactivated = user(false);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(deactivated));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) res.getBody()).getDetail()).containsIgnoringCase("not activated");
        // No session must be established for a deactivated account.
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any());
        verify(mfaService, never()).isEnabled(any());
    }

    @Test
    void login_returns403_withConsoleHint_whenRecoveryEnabled_evenWithWrongPassword() {
        AppUser deactivatedAdmin = user(false); // ADMIN, is_activated=false
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(deactivatedAdmin));
        AuthController recoveryController = newController(true);

        ResponseEntity<?> res = recoveryController.login(
            new LoginRequest("alice", "whatever-they-typed", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ProblemDetail body = (ProblemDetail) res.getBody();
        assertThat(body.getDetail())
            .containsIgnoringCase("console")
            .contains("ADMIN_RECOVERY_ENABLED=false");
        // Hint fires before (and regardless of) the password check — no oracle, no cookies.
        verify(passwordEncoder, never()).matches(any(), any());
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any());
    }

    @Test
    void login_proceeds_whenActivated_noMfa() {
        AppUser active = user(true);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);
        when(mfaService.isEnabled(active)).thenReturn(false);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref");

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref");
    }

    @Test
    void login_mfaRequired_seversLingeringSessionCookies_beforeIssuingChallenge() {
        AppUser active = user(true);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);
        when(mfaService.isEnabled(active)).thenReturn(true);
        // A DIFFERENT identity's "Remember Me" cookie is sitting on this browser.
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "stale-admin-cookie"));
        when(persistentSessionService.isTrustedDeviceFor(active, "stale-admin-cookie")).thenReturn(false);
        when(jwtUtil.generateMfaChallengeToken(active, false)).thenReturn("chal");

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) res.getBody()).get("mfaRequired")).isEqualTo(true);
        // The lingering session is severed and NO access/refresh is issued: the
        // caller stays unauthenticated until the second factor is verified.
        verify(cookieWriter).clearSessionCookies(httpRes);
        verify(cookieWriter).setMfaChallenge(httpRes, "chal");
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any());
    }

    @Test
    void login_noMfa_dropsForeignPersistentCookie_whenNotRemembering() {
        AppUser active = user(true); // id 7L
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);
        when(mfaService.isEnabled(active)).thenReturn(false);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref");
        // A "Remember Me" cookie owned by a DIFFERENT user (99) lingers on the browser.
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "foreign"));
        when(persistentSessionService.ownerUserId("foreign")).thenReturn(Optional.of(99L));

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref");
        // The foreign Remember-Me cookie is cleared so it can't re-mint user 99's session.
        verify(cookieWriter).clearPersistent(httpRes);
    }

    @Test
    void login_noMfa_keepsOwnPersistentCookie_whenNotRemembering() {
        AppUser active = user(true); // id 7L
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);
        when(mfaService.isEnabled(active)).thenReturn(false);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref");
        // The lingering cookie belongs to THIS user (a trusted device not re-ticking
        // Remember Me) — it must be preserved, not cleared.
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "mine"));
        when(persistentSessionService.ownerUserId("mine")).thenReturn(Optional.of(7L));

        controller.login(new LoginRequest("alice", "pw", false), httpReq, httpRes);

        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref");
        verify(cookieWriter, never()).clearPersistent(httpRes);
    }

    // ─── mfa/verify ──────────────────────────────────────────────────────

    @Test
    void mfaVerify_returns400_andKeepsChallenge_whenCodeInvalid() {
        AppUser active = user(true);
        httpReq.setCookies(new Cookie(AuthCookieWriter.MFA_CHALLENGE_COOKIE, "challenge"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("challenge")).thenReturn(claims);
        when(jwtUtil.isMfaChallengeToken(claims)).thenReturn(true);
        when(claims.get("uid", Long.class)).thenReturn(7L);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(active));
        when(mfaService.verifyTotpOrRecovery(active, "000000", false)).thenReturn(false);

        ResponseEntity<?> res = controller.mfaVerify(
            new com.picsou.dto.MfaDtos.MfaVerifyRequest("000000", false, false), httpReq, httpRes);

        // 400, not 401: the challenge is still valid, only the code is wrong. The
        // frontend keeps the user on the page to retry instead of bouncing to /login.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(cookieWriter, never()).clearMfaChallenge(httpRes);
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any());
    }

    @Test
    void mfaVerify_returns401_whenNoChallengeCookie() {
        ResponseEntity<?> res = controller.mfaVerify(
            new com.picsou.dto.MfaDtos.MfaVerifyRequest("123456", false, false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(mfaService, never()).verifyTotpOrRecovery(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ─── refresh ─────────────────────────────────────────────────────────

    @Test
    void refresh_returns401_andClearsCookies_whenAccountNotActivated() {
        AppUser deactivated = user(false);
        httpReq.setCookies(new Cookie("refresh_token", "rt"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(claims.getSubject()).thenReturn("alice");
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(deactivated));
        when(jwtUtil.getTokenVersion(claims)).thenReturn(3L); // matches user.tokenVersion

        ResponseEntity<?> res = controller.refresh(httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(cookieWriter).clearAuthCookies(httpRes);
        verify(jwtUtil, never()).generateAccessToken(any());
    }

    @Test
    void refresh_rotatesTokens_whenActivated_andTvMatches() {
        AppUser active = user(true);
        httpReq.setCookies(new Cookie("refresh_token", "rt"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(claims.getSubject()).thenReturn("alice");
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(jwtUtil.getTokenVersion(claims)).thenReturn(3L);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc2");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref2");

        ResponseEntity<?> res = controller.refresh(httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc2", "ref2");
    }
}
