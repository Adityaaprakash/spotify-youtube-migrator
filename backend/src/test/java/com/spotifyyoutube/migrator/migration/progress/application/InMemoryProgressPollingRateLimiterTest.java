package com.spotifyyoutube.migrator.migration.progress.application;

import com.spotifyyoutube.migrator.common.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit and concurrency tests for {@link InMemoryProgressPollingRateLimiter} (Phase 12F).
 */
@DisplayName("Phase 12F: InMemoryProgressPollingRateLimiter Tests")
class InMemoryProgressPollingRateLimiterTest {

    private ProgressRateLimitProperties properties;
    private MutableTestClock testClock;
    private InMemoryProgressPollingRateLimiter rateLimiter;

    private UUID userA;
    private UUID userB;

    @BeforeEach
    void setUp() {
        properties = new ProgressRateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxRequests(5);
        properties.setWindowSeconds(60);
        properties.setRetryAfterSeconds(5);

        testClock = new MutableTestClock(Instant.parse("2026-11-03T10:00:00Z"));
        rateLimiter = new InMemoryProgressPollingRateLimiter(properties, testClock);

        userA = UUID.randomUUID();
        userB = UUID.randomUUID();
    }

    @Nested
    @DisplayName("Quota Enforcement & Sliding Window")
    class QuotaEnforcementTests {

        @Test
        @DisplayName("1. First permitted progress request succeeds without exception")
        void firstRequestPermitted() {
            assertThatCode(() -> rateLimiter.checkLimit(userA)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("2. Requests within configured limit succeed")
        void requestsWithinLimitPermitted() {
            for (int i = 0; i < 5; i++) {
                assertThatCode(() -> rateLimiter.checkLimit(userA)).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("3. Request exceeding limit throws RateLimitExceededException with configured Retry-After")
        void requestExceedingLimitThrows() {
            for (int i = 0; i < 5; i++) {
                rateLimiter.checkLimit(userA);
            }

            assertThatThrownBy(() -> rateLimiter.checkLimit(userA))
                    .isInstanceOf(RateLimitExceededException.class)
                    .satisfies(ex -> {
                        RateLimitExceededException rle = (RateLimitExceededException) ex;
                        assertThat(rle.getRetryAfterSeconds()).isEqualTo(5);
                        assertThat(rle.getMessage()).contains("Progress polling rate limit exceeded");
                    });
        }

        @Test
        @DisplayName("4. Expired rate-limit window resets quota")
        void expiredWindowResetsQuota() {
            for (int i = 0; i < 5; i++) {
                rateLimiter.checkLimit(userA);
            }

            // Exceeded
            assertThatThrownBy(() -> rateLimiter.checkLimit(userA))
                    .isInstanceOf(RateLimitExceededException.class);

            // Advance time past the 60-second window
            testClock.advance(Duration.ofSeconds(61));

            // Quota is restored
            assertThatCode(() -> rateLimiter.checkLimit(userA)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("5. Sliding window evicts old requests incrementally")
        void slidingWindowEvictsIncrementally() {
            // 3 requests at T=0
            rateLimiter.checkLimit(userA);
            rateLimiter.checkLimit(userA);
            rateLimiter.checkLimit(userA);

            // Advance 30 seconds
            testClock.advance(Duration.ofSeconds(30));

            // 2 requests at T=30 (total in window = 5 -> reached limit)
            rateLimiter.checkLimit(userA);
            rateLimiter.checkLimit(userA);

            assertThatThrownBy(() -> rateLimiter.checkLimit(userA))
                    .isInstanceOf(RateLimitExceededException.class);

            // Advance 31 seconds -> T=61. The 3 requests from T=0 have fallen out of the 60s window.
            testClock.advance(Duration.ofSeconds(31));

            // 3 slots are now available!
            assertThatCode(() -> rateLimiter.checkLimit(userA)).doesNotThrowAnyException();
            assertThatCode(() -> rateLimiter.checkLimit(userA)).doesNotThrowAnyException();
            assertThatCode(() -> rateLimiter.checkLimit(userA)).doesNotThrowAnyException();

            // 4th request at T=61 exceeds (2 from T=30 + 3 from T=61 + 1 new = 6 > 5)
            assertThatThrownBy(() -> rateLimiter.checkLimit(userA))
                    .isInstanceOf(RateLimitExceededException.class);
        }
    }

    @Nested
    @DisplayName("User Quota Isolation")
    class UserIsolationTests {

        @Test
        @DisplayName("6. Authenticated user isolation: User A quota exhaustion does not affect User B")
        void userQuotaIsolation() {
            // Exhaust User A's quota
            for (int i = 0; i < 5; i++) {
                rateLimiter.checkLimit(userA);
            }
            assertThatThrownBy(() -> rateLimiter.checkLimit(userA))
                    .isInstanceOf(RateLimitExceededException.class);

            // User B is completely unaffected
            for (int i = 0; i < 5; i++) {
                assertThatCode(() -> rateLimiter.checkLimit(userB)).doesNotThrowAnyException();
            }
            assertThatThrownBy(() -> rateLimiter.checkLimit(userB))
                    .isInstanceOf(RateLimitExceededException.class);
        }
    }

    @Nested
    @DisplayName("Configuration & Process-Local Semantics")
    class ConfigurationTests {

        @Test
        @DisplayName("7. Disabled limiter permits unlimited requests")
        void disabledLimiterPermitsUnlimited() {
            properties.setEnabled(false);

            for (int i = 0; i < 50; i++) {
                assertThatCode(() -> rateLimiter.checkLimit(userA)).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("8. Reset method clears all process-local quota state")
        void resetClearsProcessLocalState() {
            for (int i = 0; i < 5; i++) {
                rateLimiter.checkLimit(userA);
            }
            assertThatThrownBy(() -> rateLimiter.checkLimit(userA))
                    .isInstanceOf(RateLimitExceededException.class);

            rateLimiter.reset();

            assertThatCode(() -> rateLimiter.checkLimit(userA)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("9. Null userId throws NullPointerException")
        void nullUserIdThrows() {
            assertThatThrownBy(() -> rateLimiter.checkLimit(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("userId must not be null");
        }

        @Test
        @DisplayName("10. Properties validate positive numerical values")
        void propertiesValidation() {
            assertThatThrownBy(() -> properties.setMaxRequests(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> properties.setMaxRequests(-5))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> properties.setWindowSeconds(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> properties.setWindowSeconds(-10))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> properties.setRetryAfterSeconds(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> properties.setRetryAfterSeconds(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Thread Safety & Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("11. Concurrent requests against the same user quota are thread-safe and accurately counted")
        void concurrentRequestsAccuratelyCounted() throws InterruptedException {
            int threadCount = 20;
            properties.setMaxRequests(10);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            AtomicInteger permittedCount = new AtomicInteger(0);
            AtomicInteger rejectedCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        rateLimiter.checkLimit(userA);
                        permittedCount.incrementAndGet();
                    } catch (RateLimitExceededException ex) {
                        rejectedCount.incrementAndGet();
                    } catch (Exception ex) {
                        // Unexpected
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            assertThat(permittedCount.get()).isEqualTo(10);
            assertThat(rejectedCount.get()).isEqualTo(10);
        }
    }

    /**
     * Controllable test clock for deterministic sliding-window time travel without Thread.sleep.
     */
    private static final class MutableTestClock extends Clock {
        private Instant currentInstant;
        private final ZoneId zone = ZoneId.of("UTC");

        public MutableTestClock(Instant initial) {
            this.currentInstant = initial;
        }

        public void advance(Duration duration) {
            this.currentInstant = this.currentInstant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
