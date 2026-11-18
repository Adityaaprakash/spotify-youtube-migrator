package com.spotifyyoutube.migrator.common.security.ratelimit;

import com.spotifyyoutube.migrator.common.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe, bounded, in-memory sliding-window implementation of {@link ApiRateLimiter} (Phase 16D).
 * <p>
 * Evaluates request frequency against configurable sliding windows. Enforces strict upper bounds
 * on tracked keys to prevent memory exhaustion / DoS attacks against internal state.
 */
@Service
public class InMemoryApiRateLimiter implements ApiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(InMemoryApiRateLimiter.class);

    private final RateLimitProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, SlidingWindowQuota> quotas = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryApiRateLimiter(RateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public InMemoryApiRateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "RateLimitProperties must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Override
    public void checkLimit(String clientKey, RateLimitProperties.PolicyProperties policy, String operationName) {
        Objects.requireNonNull(clientKey, "clientKey must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        if (!properties.isEnabled() || policy.getMaxRequests() <= 0) {
            return;
        }

        // Bounded memory safeguard: if capacity reached, attempt eviction
        if (quotas.size() >= properties.getMaxTrackedKeys()) {
            cleanupExpiredEntries();
            if (quotas.size() >= properties.getMaxTrackedKeys()) {
                // Saturated capacity: evict oldest entry if not already present
                if (!quotas.containsKey(clientKey)) {
                    Iterator<String> it = quotas.keySet().iterator();
                    if (it.hasNext()) {
                        quotas.remove(it.next());
                    }
                }
            }
        }

        quotas.compute(clientKey, (k, existing) -> {
            SlidingWindowQuota quota = existing != null ? existing : new SlidingWindowQuota();
            quota.consume(clock, policy, clientKey, operationName);
            return quota;
        });
    }

    @Override
    public int cleanupExpiredEntries() {
        return cleanupExpiredEntries(clock.instant());
    }

    public int cleanupExpiredEntries(Instant referenceInstant) {
        Objects.requireNonNull(referenceInstant, "referenceInstant must not be null");
        int evictedCount = 0;

        for (String key : quotas.keySet()) {
            SlidingWindowQuota result = quotas.computeIfPresent(key, (k, state) -> {
                if (state.isExpired(referenceInstant)) {
                    return null;
                }
                return state;
            });
            if (result == null) {
                evictedCount++;
            }
        }
        return evictedCount;
    }

    @Override
    public int getTrackedKeyCount() {
        return quotas.size();
    }

    @Override
    public void reset() {
        quotas.clear();
    }

    private static final class SlidingWindowQuota {
        private final Deque<Instant> timestamps = new ArrayDeque<>();
        private long currentWindowSeconds = 60;

        public synchronized void consume(
                Clock clock,
                RateLimitProperties.PolicyProperties policy,
                String clientKey,
                String operationName
        ) {
            this.currentWindowSeconds = policy.getWindowSeconds();
            Instant now = clock.instant();
            Instant cutoff = now.minusSeconds(policy.getWindowSeconds());

            // Evict expired timestamps outside the sliding window
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }

            if (timestamps.size() >= policy.getMaxRequests()) {
                log.warn("Rate limit exceeded for operation '{}' on client '{}': {} requests in past {}s (limit: {})",
                        operationName, clientKey, timestamps.size(), policy.getWindowSeconds(), policy.getMaxRequests());
                throw new RateLimitExceededException(
                        "Rate limit exceeded for " + operationName + "; please slow down requests",
                        policy.getRetryAfterSeconds()
                );
            }

            timestamps.addLast(now);
        }

        public synchronized boolean isExpired(Instant referenceInstant) {
            Instant cutoff = referenceInstant.minusSeconds(currentWindowSeconds);
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            return timestamps.isEmpty();
        }
    }
}
