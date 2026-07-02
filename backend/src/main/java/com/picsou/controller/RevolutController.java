package com.picsou.controller;

import com.picsou.config.RateLimitConfig;
import com.picsou.dto.AccountResponse;
import com.picsou.service.RevolutSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/revolut")
public class RevolutController {

    private final RevolutSyncService  revolutService;
    private final UserContext         userContext;
    private final Map<String, Bucket> revolutAuthBuckets;

    public RevolutController(
        RevolutSyncService revolutService,
        UserContext userContext,
        @Qualifier("revolutAuthBuckets") Map<String, Bucket> revolutAuthBuckets
    ) {
        this.revolutService     = revolutService;
        this.userContext        = userContext;
        this.revolutAuthBuckets = revolutAuthBuckets;
    }

    /**
     * Receives the storageState captured by the sidecar's one-time assisted headful login
     * (see docs/features/revolut-sidecar.md §3.5 -- the user logs in by hand in the sidecar's
     * browser; Java never drives credentials). Stores it encrypted and fires a background sync.
     */
    @PostMapping("/enrolment/complete")
    public ResponseEntity<?> completeEnrolment(
        @RequestBody CompleteEnrolmentRequest req,
        HttpServletRequest request
    ) {
        if (!checkAuthRateLimit(request)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many enrolment attempts. Please wait before trying again.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }
        return ResponseEntity.ok(
            revolutService.completeEnrolment(req.storageState(), userContext.currentMemberId()));
    }

    /** Manual sync using the stored session. */
    @PostMapping("/sync")
    public List<AccountResponse> sync() {
        return revolutService.sync(userContext.currentMemberId());
    }

    /** Session status: is there an active session, and when does it expire? */
    @GetMapping("/status")
    public RevolutSyncService.SessionStatusResponse getStatus() {
        return revolutService.getSessionStatus(userContext.currentMemberId());
    }

    /** Clear the stored session (forces re-enrolment). */
    @DeleteMapping("/session")
    public ResponseEntity<Void> clearSession() {
        revolutService.clearSession(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    // --- Rate limiting ---

    private boolean checkAuthRateLimit(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        Bucket bucket = revolutAuthBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createRevolutAuthBucket());
        return bucket.tryConsume(1);
    }

    record CompleteEnrolmentRequest(String storageState) {}
}
