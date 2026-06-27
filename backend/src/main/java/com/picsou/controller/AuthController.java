package com.picsou.controller;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtUtil;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.ActivationRequest;
import com.picsou.dto.LoginRequest;
import com.picsou.dto.MfaDtos;
import com.picsou.model.AppUser;
import com.picsou.model.PersistentSession;
import com.picsou.repository.AppUserRepository;
import com.picsou.model.UserRole;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import com.picsou.service.SetupAuditService;
import io.github.bucket4j.Bucket;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final Map<String, Bucket> loginBuckets;
    private final Map<String, Bucket> mfaVerifyBuckets;
    private final AuthCookieWriter cookieWriter;
    private final MfaService mfaService;
    private final PersistentSessionService persistentSessionService;
    private final SetupAuditService auditService;
    private final boolean adminRecoveryEnabled;

    /**
     * A throwaway hash with the same cost factor as {@link #passwordEncoder}.
     * When a login request ask an unknown user we still run passwordEncoder.matches
     * against this hash so the "no such user" path costs the same time as the
     * "wrong password" path.
     */
    private final String dummyPasswordHash;

    public AuthController(
        AppUserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtUtil jwtUtil,
        @org.springframework.beans.factory.annotation.Qualifier("loginBuckets") Map<String, Bucket> loginBuckets,
        @org.springframework.beans.factory.annotation.Qualifier("mfaVerifyBuckets") Map<String, Bucket> mfaVerifyBuckets,
        AuthCookieWriter cookieWriter,
        MfaService mfaService,
        PersistentSessionService persistentSessionService,
        SetupAuditService auditService,
        @org.springframework.beans.factory.annotation.Value("${app.admin-recovery.enabled:false}") boolean adminRecoveryEnabled
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.loginBuckets = loginBuckets;
        this.mfaVerifyBuckets = mfaVerifyBuckets;
        this.cookieWriter = cookieWriter;
        this.mfaService = mfaService;
        this.persistentSessionService = persistentSessionService;
        this.auditService = auditService;
        this.adminRecoveryEnabled = adminRecoveryEnabled;
        this.dummyPasswordHash = passwordEncoder.encode("login-timing-equalizer");
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
        @Valid @RequestBody LoginRequest req,
        HttpServletRequest httpReq,
        HttpServletResponse httpRes
    ) {
        String ip = getClientIp(httpReq);
        Bucket bucket = loginBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createLoginBucket());

        if (!bucket.tryConsume(1)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many login attempts. Try again in 15 minutes.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }

        AppUser user = userRepository.findByUsernameWithMember(req.username()).orElse(null);
        if (user == null) {
            // Equalize timing with the password-check path below: run a bcrypt comparison
            // against a dummy hash so a non-existent username can't be distinguished from a
            // wrong password by response latency. The response is already identical (same
            // BadCredentialsException -> 401), so this closes the enumeration oracle.
            // The result is discarded on purpose — we only want the constant-time work.
            boolean ignored = passwordEncoder.matches(req.password(), dummyPasswordHash);
            throw new BadCredentialsException("Invalid credentials");
        }

        // Break-glass recovery in progress: the admin has been deactivated and a
        // reset link was printed to the server console. Point the operator there
        // even if they mistype the (now-irrelevant) old password, and remind them
        // to turn the flag off so the next restart doesn't deactivate the account
        // again. The link itself is NOT echoed — only its location — so nothing
        // secret leaves over HTTP. Returning before the password check also means
        // the response is identical for right and wrong passwords (no oracle).
        if (adminRecoveryEnabled && user.getRole() == UserRole.ADMIN && !user.isActivated()) {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
            problem.setDetail("Admin recovery mode is active: the password reset link was "
                + "printed to the server console/logs — open it to set a new password. "
                + "Remember to set ADMIN_RECOVERY_ENABLED=false before the next restart.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
        }

        // A managed member that has only been issued an activation link still has a
        // blank password hash (""). passwordEncoder.matches(pw, "") returns false
        // *immediately* without running bcrypt, so this path would be measurably
        // faster than both the unknown-user and the wrong-password paths — a timing
        // oracle that lets an attacker distinguish "pending-activation profile" from
        // "no such user" (CWE-208). Run the same dummy-hash bcrypt round and fail
        // exactly like a wrong password so all three failing paths cost the same.
        String storedHash = user.getPasswordHash();
        if (storedHash == null || storedHash.isBlank()) {
            boolean ignored = passwordEncoder.matches(req.password(), dummyPasswordHash);
            throw new BadCredentialsException("Invalid credentials");
        }

        if (!passwordEncoder.matches(req.password(), storedHash)) {
            throw new BadCredentialsException("Invalid credentials");
        }

        // A deactivated account with the recovery flag off (or a non-admin pending
        // activation) must not get a session either: JwtAuthenticationFilter
        // requires isActivated(), so issuing tokens here would only feed an endless
        // refresh→401 loop. Fail fast with a clear message.
        if (!user.isActivated()) {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
            problem.setDetail("Account not activated. Use your activation link to set a password.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
        }

        // MFA gate: if 2FA is on AND this device is not already a trusted one,
        // we hand back a short-lived mfa_challenge cookie and demand the user
        // complete /api/auth/mfa/verify before access/refresh are issued.
        if (mfaService.isEnabled(user)) {
            String existingPersistent = extractCookie(httpReq, AuthCookieWriter.PERSISTENT_COOKIE);
            boolean trustedDevice = existingPersistent != null
                && persistentSessionService.isTrustedDeviceFor(user, existingPersistent);

            if (!trustedDevice) {
                // Password is correct but the second factor is still outstanding, so
                // the caller is NOT authenticated yet. Drop any pre-existing session
                // cookies first: on a shared/reused browser they may belong to a
                // DIFFERENT identity (e.g. a left-over no-MFA admin "Remember Me"
                // session), which would otherwise silently re-authenticate that other
                // user while this challenge is pending or abandoned.
                cookieWriter.clearSessionCookies(httpRes);
                String challenge = jwtUtil.generateMfaChallengeToken(user, req.rememberMe());
                cookieWriter.setMfaChallenge(httpRes, challenge);
                return ResponseEntity.ok(Map.of(
                    "mfaRequired", true,
                    "username", user.getUsername()
                ));
            }
            // Trusted device — fall through to issue access/refresh + rotate persistent.
        }

        completeAuthenticatedSession(user, req.rememberMe(), false, httpReq, httpRes);
        return ResponseEntity.ok(userPayload(user));
    }

    @PostMapping("/mfa/verify")
    public ResponseEntity<?> mfaVerify(
        @Valid @RequestBody MfaDtos.MfaVerifyRequest req,
        HttpServletRequest httpReq,
        HttpServletResponse httpRes
    ) {
        String ip = getClientIp(httpReq);
        Bucket bucket = mfaVerifyBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createMfaVerifyBucket());
        if (!bucket.tryConsume(1)) {
            cookieWriter.clearMfaChallenge(httpRes);
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many verification attempts. Please log in again in 15 minutes.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }

        String challengeCookie = extractCookie(httpReq, AuthCookieWriter.MFA_CHALLENGE_COOKIE);
        if (challengeCookie == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "No MFA challenge in progress"));
        }

        Claims claims;
        try {
            claims = jwtUtil.validateAndParse(challengeCookie);
        } catch (JwtException ex) {
            cookieWriter.clearMfaChallenge(httpRes);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid MFA challenge"));
        }
        if (!jwtUtil.isMfaChallengeToken(claims)) {
            cookieWriter.clearMfaChallenge(httpRes);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Long userId = claims.get("uid", Long.class);
        AppUser user = userRepository.findByIdWithMember(userId)
            .orElseThrow(() -> new BadCredentialsException("User not found"));

        boolean isRecovery = Boolean.TRUE.equals(req.isRecoveryCode());
        if (!mfaService.verifyTotpOrRecovery(user, req.code(), isRecovery)) {
            // 400, not 401: the challenge cookie is still valid — only the code is
            // wrong. The frontend treats 401 as "challenge gone, re-login" and
            // bounces to /login; a bad code must keep the user on the page to retry.
            // The challenge cookie is intentionally left intact here.
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid verification code"));
        }

        boolean rememberMe = jwtUtil.getRememberMeClaim(claims);
        boolean trustDevice = Boolean.TRUE.equals(req.trustDevice());
        completeAuthenticatedSession(user, rememberMe || trustDevice, trustDevice, httpReq, httpRes);
        cookieWriter.clearMfaChallenge(httpRes);
        return ResponseEntity.ok(userPayload(user));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest httpReq, HttpServletResponse httpRes) {
        String refreshToken = extractCookie(httpReq, "refresh_token");

        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "No refresh token"));
        }

        try {
            var claims = jwtUtil.validateAndParse(refreshToken);
            if (!jwtUtil.isRefreshToken(claims)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            String username = claims.getSubject();
            AppUser user = userRepository.findByUsernameWithMember(username)
                .orElseThrow(() -> new BadCredentialsException("User not found"));

            // Reject if the token version was bumped (credential change / recovery)
            // OR the account has since been deactivated. Treating !isActivated() as
            // revoked clears the cookies and breaks the refresh→401 loop for any
            // session that was minted before deactivation — consistent with the
            // isActivated() gate in JwtAuthenticationFilter / PersistentTokenAuthFilter.
            Long tv = jwtUtil.getTokenVersion(claims);
            if (tv == null || tv != user.getTokenVersion() || !user.isActivated()) {
                clearTokenCookies(httpRes);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Token revoked"));
            }

            String newAccess = jwtUtil.generateAccessToken(user);
            String newRefresh = jwtUtil.generateRefreshToken(user); // rotation

            setTokenCookies(httpRes, newAccess, newRefresh);
            return ResponseEntity.ok(Map.of(
                "username", user.getUsername(),
                "role", user.getRole().name(),
                "memberId", user.getMember().getId(),
                "displayName", user.getMember().getDisplayName()
            ));

        } catch (Exception ex) {
            clearTokenCookies(httpRes);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpReq, HttpServletResponse httpRes) {
        // Best-effort revoke the persistent series so the cookie can't be replayed
        // even if the browser failed to honour Set-Cookie Max-Age=0.
        String persistent = extractCookie(httpReq, AuthCookieWriter.PERSISTENT_COOKIE);
        if (persistent != null) {
            persistentSessionService.seriesFromCookie(persistent)
                .ifPresent(persistentSessionService::revokeBySeriesId);
        }
        clearTokenCookies(httpRes);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/username")
    public ResponseEntity<?> changeUsername(
        @AuthenticationPrincipal AppUser user,
        @Valid @RequestBody ChangeUsernameRequest req,
        HttpServletResponse httpRes
    ) {
        String newUsername = req.newUsername().trim();
        if (newUsername.equals(user.getUsername())) {
            return ResponseEntity.ok(Map.of("username", user.getUsername()));
        }
        if (userRepository.existsByUsername(newUsername)) {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            problem.setDetail("Username already taken");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }
        user.setUsername(newUsername);
        userRepository.save(user);
        String newAccess = jwtUtil.generateAccessToken(user);
        String newRefresh = jwtUtil.generateRefreshToken(user);
        setTokenCookies(httpRes, newAccess, newRefresh);
        return ResponseEntity.ok(Map.of("username", newUsername));
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
        @AuthenticationPrincipal AppUser user,
        @Valid @RequestBody ChangePasswordRequest req,
        HttpServletRequest httpReq,
        HttpServletResponse httpRes
    ) {

        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        // Invalidate every outstanding access/refresh JWT across all devices.
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        // Kick every Remember-Me browser. The caller's persistent cookie is
        // dropped along with the rest — they will need to log back in on this
        // browser too if they ticked Remember-Me previously.
        persistentSessionService.revokeAllForUser(user.getId());

        // Re-issue access+refresh for the calling browser so the user does
        // not get logged out by their own action. The new cookies carry the
        // bumped tokenVersion; old cookies on this browser are overwritten.
        String newAccess = jwtUtil.generateAccessToken(user);
        String newRefresh = jwtUtil.generateRefreshToken(user);
        setTokenCookies(httpRes, newAccess, newRefresh);
        cookieWriter.clearPersistent(httpRes);

        return ResponseEntity.ok(Map.of("message", "Password updated successfully"));
    }

    @PostMapping("/activate/{token}")
    public ResponseEntity<?> activate(
        @PathVariable String token,
        @Valid @RequestBody ActivationRequest req,
        HttpServletRequest httpReq
    ) {
        AppUser user = userRepository.findByActivationToken(token)
            .orElseThrow(() -> new BadCredentialsException("Invalid activation token"));

        if (user.getActivationTokenExpires() != null &&
            user.getActivationTokenExpires().isBefore(Instant.now())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Activation token has expired"));
        }

        if (!req.acknowledgedWarning()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "You must acknowledge the data access warning"));
        }

        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setActivationToken(null);
        user.setActivationTokenExpires(null);
        user.setActivated(true);
        user.setAcknowledgedWarning(true);
        // This endpoint is the shared sink for new-member activation AND
        // admin-initiated password resets (FamilyService.resetPasswordToken issues
        // the token; the member then activates here) AND admin-recovery completion.
        // A reset may be a response to a compromise, so — exactly like the
        // self-service change-password flow — invalidate every outstanding session:
        // bump tokenVersion to revoke all access/refresh JWTs, and wipe the user's
        // Remember-Me persistent sessions. Without this, an attacker's pre-existing
        // tokens/cookies would survive an admin password reset (CWE-613/640).
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        persistentSessionService.revokeAllForUser(user.getId());

        if (user.getRole() == UserRole.ADMIN) {
            auditService.record("admin.recovery.completed", user.getUsername(), httpReq, null);
        }

        return ResponseEntity.ok(Map.of("message", "Account activated successfully"));
    }

    // ─── Session helpers ──────────────────────────────────────────────────────
    // Cookie attributes (HttpOnly/SameSite/Secure) live in AuthCookieWriter so
    // every emitter (controller, MFA flow, persistent-token filter) stays aligned.

    /**
     * Issues access + refresh cookies and, when {@code issuePersistent} is true,
     * also creates a fresh PersistentSession in DB and writes the persistent_token
     * cookie. Use {@code trustedFor2fa} to mark the device as MFA-trusted for the
     * silent-bypass flow.
     */
    private void completeAuthenticatedSession(
        AppUser user,
        boolean issuePersistent,
        boolean trustedFor2fa,
        HttpServletRequest httpReq,
        HttpServletResponse httpRes
    ) {
        cookieWriter.setAccessAndRefresh(httpRes,
            jwtUtil.generateAccessToken(user),
            jwtUtil.generateRefreshToken(user));

        if (issuePersistent) {
            PersistentSessionService.IssueResult issued = persistentSessionService.issue(
                user,
                trustedFor2fa,
                httpReq.getHeader("User-Agent"),
                getClientIp(httpReq)
            );
            PersistentSession session = issued.session();
            long secondsUntilExpiry = Math.max(
                ChronoUnit.SECONDS.between(Instant.now(), session.getExpiresAt()),
                0
            );
            cookieWriter.setPersistent(httpRes, issued.cookieValue(), secondsUntilExpiry);
        } else {
            // Not remembering this device. If a "Remember Me" cookie from a DIFFERENT
            // identity is still on this browser (shared/reused machine), drop it so it
            // can't silently re-mint that other user's session via the persistent-token
            // filter once this access token lapses. A persistent cookie that belongs to
            // THIS user — a trusted device logging in without re-ticking Remember Me —
            // is left intact so we don't needlessly un-trust the device.
            String existingPersistent = extractCookie(httpReq, AuthCookieWriter.PERSISTENT_COOKIE);
            if (existingPersistent != null && !existingPersistent.isBlank()
                && persistentSessionService.ownerUserId(existingPersistent)
                    .filter(ownerId -> ownerId.equals(user.getId()))
                    .isEmpty()) {
                cookieWriter.clearPersistent(httpRes);
            }
        }
    }

    private Map<String, Object> userPayload(AppUser user) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", user.getUsername());
        body.put("role", user.getRole().name());
        body.put("memberId", user.getMember().getId());
        body.put("displayName", user.getMember().getDisplayName());
        return body;
    }

    private void setTokenCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        cookieWriter.setAccessAndRefresh(response, accessToken, refreshToken);
    }

    private void clearTokenCookies(HttpServletResponse response) {
        cookieWriter.clearAuthCookies(response);
    }

    private String extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (var cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private String getClientIp(HttpServletRequest request) {
        // Never trust X-Forwarded-For from the client — it is user-controllable and
        // would allow rate-limit bypass by spoofing IPs. Use only the TCP-level remote
        // address, which is the nginx container's internal IP in production (the only
        // valid entry point on the picsou-net Docker bridge network).
        return request.getRemoteAddr();
    }

    record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8, max = 128) String newPassword
    ) {}

    record ChangeUsernameRequest(
        @NotBlank @Size(min = 3, max = 50)
        @jakarta.validation.constraints.Pattern(regexp = "[a-zA-Z0-9._-]+", message = "Username may only contain letters, digits, dots, underscores and hyphens")
        String newUsername
    ) {}
}
