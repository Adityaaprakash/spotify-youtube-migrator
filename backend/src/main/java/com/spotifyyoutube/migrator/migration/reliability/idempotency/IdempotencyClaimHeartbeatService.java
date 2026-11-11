package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service managing periodic, bounded claim lease heartbeats for active idempotency leader executions (Phase 13E).
 * <p>
 * Ensures winning leader threads executing long-running preparation (such as external YouTube playlist creation)
 * continuously maintain valid claim ownership in PostgreSQL without holding database transactions open,
 * while enforcing strict generational fencing, wall-clock time expiration, and ownership loss detection.
 */
@Service
public class IdempotencyClaimHeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyClaimHeartbeatService.class);
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 15_000L;

    private final MigrationIdempotencyRepository repository;
    private final TransactionTemplate requiresNewTemplate;
    private final ScheduledExecutorService scheduler;
    private final long heartbeatIntervalMs;
    private final ConcurrentHashMap<UUID, ActiveClaimHeartbeatContext> activeClaims = new ConcurrentHashMap<>();

    @Autowired
    public IdempotencyClaimHeartbeatService(
            MigrationIdempotencyRepository repository,
            PlatformTransactionManager transactionManager
    ) {
        this(repository, transactionManager, null, DEFAULT_HEARTBEAT_INTERVAL_MS);
    }

    public IdempotencyClaimHeartbeatService(
            MigrationIdempotencyRepository repository,
            PlatformTransactionManager transactionManager,
            ScheduledExecutorService scheduler,
            long heartbeatIntervalMs
    ) {
        this.repository = Objects.requireNonNull(repository, "MigrationIdempotencyRepository must not be null");
        Objects.requireNonNull(transactionManager, "PlatformTransactionManager must not be null");
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        this.heartbeatIntervalMs = heartbeatIntervalMs > 0 ? heartbeatIntervalMs : DEFAULT_HEARTBEAT_INTERVAL_MS;
        if (scheduler != null) {
            this.scheduler = scheduler;
        } else {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "idempotency-claim-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    /**
     * Registers an actively acquired idempotency claim for periodic heartbeat renewals.
     *
     * @param claimId         claim record UUID
     * @param userId          authenticated user UUID
     * @param idempotencyKey  client idempotency key
     * @param claimOwner      winning leader ownership identity
     * @param claimGeneration current leader fencing generation
     * @param leaseDuration   validity duration to extend on each heartbeat
     */
    public void registerClaim(
            UUID claimId,
            UUID userId,
            String idempotencyKey,
            String claimOwner,
            long claimGeneration,
            Duration leaseDuration
    ) {
        if (claimId == null || claimOwner == null || claimOwner.isBlank()) {
            return;
        }

        Duration duration = leaseDuration != null && !leaseDuration.isNegative() && !leaseDuration.isZero()
                ? leaseDuration : Duration.ofSeconds(60);

        ActiveClaimHeartbeatContext context = new ActiveClaimHeartbeatContext(
                claimId,
                userId,
                idempotencyKey,
                claimOwner,
                claimGeneration,
                duration
        );

        ActiveClaimHeartbeatContext existing = activeClaims.put(claimId, context);
        if (existing != null) {
            existing.cancelFuture();
        }

        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> renewClaimHeartbeat(claimId),
                heartbeatIntervalMs,
                heartbeatIntervalMs,
                TimeUnit.MILLISECONDS
        );
        context.setScheduledFuture(future);

        log.debug("Registered claim heartbeat for claim {} (gen {}) by owner '{}' with interval {}ms",
                claimId, claimGeneration, claimOwner, heartbeatIntervalMs);
    }

    /**
     * Unregisters an idempotency claim from heartbeat renewals upon normal completion, failure, or interruption.
     *
     * @param claimId claim record UUID
     */
    public void unregisterClaim(UUID claimId) {
        if (claimId == null) {
            return;
        }
        ActiveClaimHeartbeatContext removed = activeClaims.remove(claimId);
        if (removed != null) {
            removed.cancelFuture();
            log.debug("Unregistered claim heartbeat for claim {} (gen {})", claimId, removed.claimGeneration());
        }
    }

    /**
     * Checks whether lease ownership was detected as lost during a previous heartbeat attempt.
     *
     * @param claimId claim record UUID
     * @return true if claim was registered and lost ownership, false otherwise
     */
    public boolean isOwnershipLost(UUID claimId) {
        if (claimId == null) {
            return false;
        }
        ActiveClaimHeartbeatContext context = activeClaims.get(claimId);
        return context != null && context.isOwnershipLost();
    }

    /**
     * Directly renews the claim heartbeat for a specific claim synchronously.
     * Useful for deterministic testing and manual verifications.
     *
     * @param claimId claim record UUID
     * @return true if successfully renewed in database, false if ownership was lost or claim expired
     */
    public boolean renewClaimDirectly(UUID claimId) {
        return renewClaimHeartbeat(claimId);
    }

    /**
     * Returns the count of currently registered active claim heartbeats.
     *
     * @return count of active claim heartbeats
     */
    public int getActiveClaimCount() {
        return activeClaims.size();
    }

    private boolean renewClaimHeartbeat(UUID claimId) {
        ActiveClaimHeartbeatContext context = activeClaims.get(claimId);
        if (context == null) {
            return false;
        }

        if (context.isOwnershipLost()) {
            context.cancelFuture();
            return false;
        }

        try {
            Integer rows = requiresNewTemplate.execute(status ->
                    repository.renewClaimDatabaseTime(
                            context.claimId(),
                            context.claimOwner(),
                            context.claimGeneration(),
                            context.leaseDuration().toSeconds()
                    )
            );

            if (rows != null && rows == 1) {
                log.debug("Heartbeat successfully renewed claim {} (gen {}) for owner {}",
                        claimId, context.claimGeneration(), context.claimOwner());
                return true;
            } else {
                log.warn("Heartbeat renewal returned 0 rows for claim {} (gen {}). "
                                + "Ownership lost (lease expired, taken over, or completed).",
                        claimId, context.claimGeneration());
                context.markOwnershipLost();
                context.cancelFuture();
                return false;
            }
        } catch (Exception ex) {
            log.warn("Heartbeat renewal failed for claim {} due to exception: {}", claimId, ex.getMessage());
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        activeClaims.values().forEach(ActiveClaimHeartbeatContext::cancelFuture);
        activeClaims.clear();
        scheduler.shutdownNow();
    }

    /**
     * Context tracking an active leader's claim heartbeat lease.
     */
    private static final class ActiveClaimHeartbeatContext {
        private final UUID claimId;
        private final UUID userId;
        private final String idempotencyKey;
        private final String claimOwner;
        private final long claimGeneration;
        private final Duration leaseDuration;
        private final AtomicBoolean ownershipLost = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> scheduledFuture;

        ActiveClaimHeartbeatContext(
                UUID claimId,
                UUID userId,
                String idempotencyKey,
                String claimOwner,
                long claimGeneration,
                Duration leaseDuration
        ) {
            this.claimId = claimId;
            this.userId = userId;
            this.idempotencyKey = idempotencyKey;
            this.claimOwner = claimOwner;
            this.claimGeneration = claimGeneration;
            this.leaseDuration = leaseDuration;
        }

        UUID claimId() {
            return claimId;
        }

        UUID userId() {
            return userId;
        }

        String idempotencyKey() {
            return idempotencyKey;
        }

        String claimOwner() {
            return claimOwner;
        }

        long claimGeneration() {
            return claimGeneration;
        }

        Duration leaseDuration() {
            return leaseDuration;
        }

        boolean isOwnershipLost() {
            return ownershipLost.get();
        }

        void markOwnershipLost() {
            ownershipLost.set(true);
        }

        void setScheduledFuture(ScheduledFuture<?> future) {
            this.scheduledFuture = future;
        }

        void cancelFuture() {
            ScheduledFuture<?> future = this.scheduledFuture;
            if (future != null && !future.isCancelled()) {
                future.cancel(true);
            }
        }
    }
}
