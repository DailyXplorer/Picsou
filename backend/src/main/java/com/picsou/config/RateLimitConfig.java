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
}
