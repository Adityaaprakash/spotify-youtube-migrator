package com.spotifyyoutube.migrator.migration.progress.application;

import com.spotifyyoutube.migrator.common.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe, in-memory sliding-window implementation of {@link ProgressPollingRateLimiter} (Phase 12F).
 * <p>
 * Tracks request timestamps per authenticated user ID in memory using sliding windows.
 * <p>
 * <strong>Architecture & Scalability Trade-offs:</strong>
 * <ul>
 *   <li><strong>Process-Local:</strong> State is maintained within the JVM process. Quota resets on application restart.</li>
 *   <li><strong>Cluster Limitation:</strong> Enforces limits per application instance; does not synchronize state across
 *       clustered instances without a distributed cache (e.g. Redis, Phase 19).</li>
 *   <li><strong>Encapsulated Contract:</strong> Designed behind {@link ProgressPollingRateLimiter} so a distributed implementation
 *       can be swapped in seamlessly if clustered deployments require centralized quota coordination.</li>
 * </ul>
 */
@Service
public class InMemoryProgressPollingRateLimiter implements ProgressPollingRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(InMemoryProgressPollingRateLimiter.class);

    private final ProgressRateLimitProperties properties;
    private final Clock clock;
    private final ConcurrentMap<UUID, UserQuotaState> userQuotas = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryProgressPollingRateLimiter(ProgressRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public InMemoryProgressPollingRateLimiter(ProgressRateLimitProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "ProgressRateLimitProperties must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Override
    public void checkLimit(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");

        if (!properties.isEnabled()) {
            return;
        }

        userQuotas.compute(userId, (id, existing) -> {
            UserQuotaState state = existing != null ? existing : new UserQuotaState();
            state.consume(clock, properties, userId);
            return state;
        });
    }

    /**
     * Bounded, thread-safe cleanup of inactive/expired user quota entries (Phase 12G-G).
     * <p>
     * Safely evicts map entries where all request timestamps have expired beyond the sliding window.
     * Uses atomic {@link ConcurrentMap#computeIfPresent(Object, java.util.function.BiFunction)} to prevent
     * removing an entry while another thread is actively updating its quota.
     *
     * @return the number of evicted inactive user quota entries
     */
    public int cleanupExpiredEntries() {
        return cleanupExpiredEntries(clock.instant());
    }

    /**
     * Bounded, thread-safe cleanup of inactive/expired user quota entries at a specified reference instant.
     *
     * @param referenceInstant the instant against which the sliding window cutoff is calculated
     * @return the number of evicted inactive user quota entries
     */
    public int cleanupExpiredEntries(Instant referenceInstant) {
        Objects.requireNonNull(referenceInstant, "referenceInstant must not be null");
        int evictedCount = 0;
        long windowSeconds = properties.getWindowSeconds();

        for (UUID userId : userQuotas.keySet()) {
            UserQuotaState result = userQuotas.computeIfPresent(userId, (id, state) -> {
                if (state.isExpired(referenceInstant, windowSeconds)) {
                    return null; // Atomic removal from ConcurrentHashMap
                }
                return state;
            });
            if (result == null) {
                evictedCount++;
            }
        }
        return evictedCount;
    }

    /**
     * Returns the number of user quotas currently tracked in process memory.
     *
     * @return tracked user quota count
     */
    public int getTrackedUserCount() {
        return userQuotas.size();
    }

    /**
     * Resets process-local quota state. Primarily intended for deterministic test teardown.
     */
    public void reset() {
        userQuotas.clear();
    }

    private static final class UserQuotaState {
        private final Deque<Instant> requestTimestamps = new ArrayDeque<>();

        public synchronized void consume(Clock clock, ProgressRateLimitProperties properties, UUID userId) {
            Instant now = clock.instant();
            Instant cutoff = now.minusSeconds(properties.getWindowSeconds());

            // Evict expired timestamps outside the sliding window
            while (!requestTimestamps.isEmpty() && requestTimestamps.peekFirst().isBefore(cutoff)) {
                requestTimestamps.pollFirst();
            }

            if (requestTimestamps.size() >= properties.getMaxRequests()) {
                log.warn("Rate limit exceeded for user {}: {} requests in past {}s window (limit: {})",
                        userId, requestTimestamps.size(), properties.getWindowSeconds(), properties.getMaxRequests());
                throw new RateLimitExceededException(
                        "Progress polling rate limit exceeded; please slow down requests",
                        properties.getRetryAfterSeconds()
                );
            }

            requestTimestamps.addLast(now);
        }

        public synchronized boolean isExpired(Instant now, long windowSeconds) {
            Instant cutoff = now.minusSeconds(windowSeconds);
            while (!requestTimestamps.isEmpty() && requestTimestamps.peekFirst().isBefore(cutoff)) {
                requestTimestamps.pollFirst();
            }
            return requestTimestamps.isEmpty();
        }
    }
}
