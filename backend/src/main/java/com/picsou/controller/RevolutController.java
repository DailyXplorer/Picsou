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
     * On-demand sync: blank phoneNumber/passcode falls back to remembered credentials. This call
     * can block for several minutes while the sidecar waits for the user to approve a push
     * notification on their phone -- see {@code RevolutSyncService}.
     */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(@RequestBody SyncRequest req, HttpServletRequest request) {
        if (!checkAuthRateLimit(request)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many sync attempts. Please wait before trying again.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }
        return ResponseEntity.ok(revolutService.sync(
            userContext.currentMemberId(), req.phoneNumber(), req.passcode(), req.remember()));
    }

    /** Connection status: are credentials remembered, and when did we last sync? */
    @GetMapping("/status")
    public RevolutSyncService.StatusResponse getStatus() {
        return revolutService.getStatus(userContext.currentMemberId());
    }

    /** Forgets any remembered credentials (accounts already synced are left untouched). */
    @DeleteMapping("/session")
    public ResponseEntity<Void> disconnect() {
        revolutService.disconnect(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    // --- Rate limiting ---

    private boolean checkAuthRateLimit(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        Bucket bucket = revolutAuthBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createRevolutAuthBucket());
        return bucket.tryConsume(1);
    }

    record SyncRequest(String phoneNumber, String passcode, boolean remember) {}
}
