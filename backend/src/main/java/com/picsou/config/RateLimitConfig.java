package com.picsou.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class RateLimitConfig {

    /**
     * Per-IP login rate limiter: 5 attempts per 15 minutes.
     * Uses a ConcurrentHashMap of Bucket4j buckets keyed by IP address.
     */
    @Bean("loginBuckets")
    public Map<String, Bucket> loginBuckets() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Per-IP sync rate limiter: 10 requests per minute.
     */
    @Bean("syncBuckets")
    public Map<String, Bucket> syncBuckets() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Per-IP TR auth rate limiter: 3 attempts per 10 minutes.
     * Strict because each attempt sends an SMS.
     */
    @Bean("trAuthBuckets")
    public Map<String, Bucket> trAuthBuckets() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Per-IP BoursoBank auth rate limiter: 5 attempts per 15 minutes.
     */
    @Bean("boursoAuthBuckets")
    public Map<String, Bucket> boursoAuthBuckets() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Per-IP Revolut enrolment rate limiter: 5 attempts per 15 minutes.
     * The sidecar's own login is already rate-limited by Revolut itself (spec §3.5: repeated
     * WEB logins trigger captchas/throttling within hours), but this endpoint just receives a
     * storageState blob -- still worth bounding against abuse of the encryption/DB write path.
     */
    @Bean("revolutAuthBuckets")
    public Map<String, Bucket> revolutAuthBuckets() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Per-IP setup wizard rate limiter: 10 mutating requests per minute.
     * Tight because the endpoints are unauthenticated until setup completes
     * — without this, a fresh install is exposed to admin-seeding floods
     * from anyone who can reach port 8080 before the legitimate operator.
     */
    @Bean("setupBuckets")
    public Map<String, Bucket> setupBuckets() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Per-IP MFA verify rate limiter: 5 attempts per 15 minutes.
     * The 6-digit TOTP space is only 1M; without throttling an attacker with
     * a stolen mfa_challenge cookie could brute-force in under a minute.
     */
    @Bean("mfaVerifyBuckets")
    public Map<String, Bucket> mfaVerifyBuckets() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Per-IP MFA enrollment rate limiter: 10 requests per hour.
     * QR generation is CPU-bound (PNG encoding) and enrollment is per-user
     * one-time — anything beyond a handful per hour from the same IP is abuse.
     */
    @Bean("mfaEnrollBuckets")
    public Map<String, Bucket> mfaEnrollBuckets() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Per-user GDPR data export rate limiter: 5 exports per hour.
     * Each export streams the user's full graph (accounts, holdings,
     * transactions, ...) — expensive to build and to ship over the wire,
     * and a successful re-auth shouldn't unlock unbounded dumps.
     */
    @Bean("exportBuckets")
    public Map<String, Bucket> exportBuckets() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Per-IP merchant-logo proxy rate limiter: 60 requests per minute.
     * Generous because a single page can render many avatars, but bounded so the
     * opt-in proxy can't be turned into an open relay against the upstream icon
     * service. Cache hits still count — the bucket protects the network egress, not
     * just the cache.
     */
    @Bean("logoBuckets")
    public Map<String, Bucket> logoBuckets() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Per-key MCP rate limiter: keyed by access-key id (not IP), since one key may serve many
     * tool calls from a single AI client. Lives in the {@code AccessKeyAuthFilter}, which creates
     * a bucket lazily on first use and shares it across that key's requests.
     */
    @Bean("mcpKeyBuckets")
    public Map<Long, Bucket> mcpKeyBuckets() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Per-member access-key creation limiter: keyed by member id (not IP), because the
     * {@code POST /api/access-keys} endpoint is cookie-authenticated and self-service — the member is
     * the correct abuse boundary, so an attacker with a stolen session can't mint keys faster than this
     * regardless of IP rotation.
     */
    @Bean("accessKeyCreateBuckets")
    public Map<Long, Bucket> accessKeyCreateBuckets() {
        return new ConcurrentHashMap<>();
    }

    public static Bucket createLoginBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(5, Duration.ofMinutes(15))
                .build())
            .build();
    }

    public static Bucket createSyncBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(10)
                .refillIntervally(10, Duration.ofMinutes(1))
                .build())
            .build();
    }

    public static Bucket createTrAuthBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(3)
                .refillIntervally(3, Duration.ofMinutes(10))
                .build())
            .build();
    }

    public static Bucket createBoursoAuthBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(5, Duration.ofMinutes(15))
                .build())
            .build();
    }

    public static Bucket createRevolutAuthBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(5, Duration.ofMinutes(15))
                .build())
            .build();
    }

    public static Bucket createSetupBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(10)
                .refillIntervally(10, Duration.ofMinutes(1))
                .build())
            .build();
    }

    public static Bucket createMfaVerifyBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(5, Duration.ofMinutes(15))
                .build())
            .build();
    }

    public static Bucket createMfaEnrollBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(10)
                .refillIntervally(10, Duration.ofMinutes(60))
                .build())
            .build();
    }

    public static Bucket createExportBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(5, Duration.ofMinutes(60))
                .build())
            .build();
    }

    public static Bucket createLogoBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(60)
                .refillIntervally(60, Duration.ofMinutes(1))
                .build())
            .build();
    }

    /**
     * Per-key MCP throttle: 120 requests per minute. Generous enough for an interactive AI client
     * (each user turn can fan out into several tool calls) while capping a runaway or hostile key.
     */
    public static Bucket createMcpKeyBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(120)
                .refillIntervally(120, Duration.ofMinutes(1))
                .build())
            .build();
    }

    /**
     * Per-member access-key creation throttle: 10 new keys per hour. Minting a key is a deliberate,
     * infrequent action; anything past a handful an hour from one member is a runaway script or a
     * hijacked session, so cap it without hindering normal use.
     */
    public static Bucket createAccessKeyCreateBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(10)
                .refillIntervally(10, Duration.ofMinutes(60))
                .build())
            .build();
    }
}
