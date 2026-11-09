package com.spotifyyoutube.migrator.migration.reliability.scheduling;

import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service managing periodic, bounded lease heartbeats for actively executing workers (Phase 13D-D).
 * <p>
 * Ensures workers continuously maintain valid lease ownership during long-running migration processing
 * while enforcing strict generational fencing, wall-clock time expiration, and ownership loss detection.
 */
@Service
public class DurableLeaseHeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(DurableLeaseHeartbeatService.class);

    private final MigrationExecutionRepository executionRepository;
    private final DurableSchedulerProperties properties;
    private final ConcurrentHashMap<UUID, ActiveLeaseContext> activeLeases = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    @Autowired
    public DurableLeaseHeartbeatService(
            MigrationExecutionRepository executionRepository,
            DurableSchedulerProperties properties
    ) {
        this.executionRepository = Objects.requireNonNull(
                executionRepository, "MigrationExecutionRepository must not be null");
        this.properties = Objects.requireNonNull(
                properties, "DurableSchedulerProperties must not be null");
    }

    /**
     * Registers an actively claimed execution lease for periodic heartbeat renewal.
     *
     * @param executionId execution identifier
     * @param workerId worker node/thread identity
     * @param leaseGeneration acquired fencing generation
     * @param leaseDurationSeconds lease validity duration in seconds
     */
    public void registerLease(
            UUID executionId,
            String workerId,
            long leaseGeneration,
            long leaseDurationSeconds
    ) {
        if (executionId == null || workerId == null || workerId.isBlank()) {
            return;
        }
        activeLeases.put(executionId, new ActiveLeaseContext(
                executionId,
                workerId.trim(),
                leaseGeneration,
                leaseDurationSeconds > 0 ? leaseDurationSeconds : properties.getLeaseDurationSeconds()
        ));
        log.debug("Registered lease heartbeat for execution {} (gen {}) by worker '{}'",
                executionId, leaseGeneration, workerId);
    }

    /**
     * Convenience overload registering an active lease using the default configured lease duration.
     *
     * @param executionId execution identifier
     * @param workerId worker node/thread identity
     * @param leaseGeneration acquired fencing generation
     */
    public void registerLease(UUID executionId, String workerId, long leaseGeneration) {
        registerLease(executionId, workerId, leaseGeneration, properties.getLeaseDurationSeconds());
    }

    /**
     * Explicitly marks an active lease context as having lost ownership.
     *
     * @param executionId execution identifier
     */
    public void markOwnershipLost(UUID executionId) {
        if (executionId != null) {
            ActiveLeaseContext ctx = activeLeases.get(executionId);
            if (ctx != null) {
                ctx.markOwnershipLost();
            }
        }
    }

    /**
     * Unregisters an execution lease from heartbeat renewals upon worker completion or shutdown.
     *
     * @param executionId execution identifier
     */
    public void unregisterLease(UUID executionId) {
        if (executionId != null) {
            ActiveLeaseContext removed = activeLeases.remove(executionId);
            if (removed != null) {
                log.debug("Unregistered lease heartbeat for execution {} (gen {})",
                        executionId, removed.leaseGeneration());
            }
        }
    }

    /**
     * Checks whether lease ownership was detected as lost during a previous heartbeat attempt.
     *
     * @param executionId execution identifier
     * @return true if registered context exists and ownership was lost, or false if healthy/unregistered
     */
    public boolean isOwnershipLost(UUID executionId) {
        if (executionId == null) {
            return false;
        }
        ActiveLeaseContext ctx = activeLeases.get(executionId);
        return ctx != null && ctx.isOwnershipLost();
    }

    /**
     * Checks if an execution is currently registered for heartbeat renewal.
     *
     * @param executionId execution identifier
     * @return true if actively registered
     */
    public boolean isRegistered(UUID executionId) {
        return executionId != null && activeLeases.containsKey(executionId);
    }

    /**
     * Returns the count of currently registered active leases.
     *
     * @return count of active leases
     */
    public int getActiveLeaseCount() {
        return activeLeases.size();
    }

    /**
     * Directly renews the lease heartbeat for a specific execution.
     *
     * @param executionId execution identifier
     * @return true if successfully renewed in database, false if ownership lost or rejected
     */
    public boolean renewLease(UUID executionId) {
        if (executionId == null) {
            return false;
        }
        ActiveLeaseContext ctx = activeLeases.get(executionId);
        if (ctx == null) {
            return false;
        }
        return doRenewLease(ctx);
    }

    /**
     * Periodic scheduled heartbeat renewal for all registered active leases.
     * Scheduled at a bounded interval shorter than lease duration.
     *
     * @return number of successfully renewed leases
     */
    @Scheduled(fixedDelayString = "${migration.scheduler.heartbeat-interval-ms:15000}")
    public int renewActiveLeases() {
        if (activeLeases.isEmpty()) {
            return 0;
        }

        int renewed = 0;
        for (ActiveLeaseContext ctx : activeLeases.values()) {
            if (ctx.isOwnershipLost()) {
                continue;
            }
            if (doRenewLease(ctx)) {
                renewed++;
            }
        }
        return renewed;
    }

    private boolean doRenewLease(ActiveLeaseContext ctx) {
        try {
            int rows = executionRepository.renewLeaseHeartbeat(
                    ctx.executionId(),
                    ctx.workerId(),
                    ctx.leaseGeneration(),
                    ctx.leaseDurationSeconds()
            );

            if (rows == 1) {
                log.debug("Heartbeat successfully renewed lease for execution {} (gen {})",
                        ctx.executionId(), ctx.leaseGeneration());
                return true;
            } else {
                log.warn("Heartbeat renewal returned 0 rows for execution {} (gen {}). "
                                + "Ownership lost (lease expired, taken over, or terminal status).",
                        ctx.executionId(), ctx.leaseGeneration());
                ctx.markOwnershipLost();
                return false;
            }
        } catch (Exception ex) {
            log.error("Database error during heartbeat renewal for execution {} (gen {}): {}",
                    ctx.executionId(), ctx.leaseGeneration(), ex.getMessage());
            return false;
        }
    }

    /**
     * Signals graceful shutdown of heartbeat service.
     */
    public void stop() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Mutable context tracking an active execution lease and its ownership status.
     */
    public static class ActiveLeaseContext {
        private final UUID executionId;
        private final String workerId;
        private final long leaseGeneration;
        private final long leaseDurationSeconds;
        private final AtomicBoolean ownershipLost = new AtomicBoolean(false);

        public ActiveLeaseContext(
                UUID executionId,
                String workerId,
                long leaseGeneration,
                long leaseDurationSeconds
        ) {
            this.executionId = Objects.requireNonNull(executionId, "executionId must not be null");
            this.workerId = Objects.requireNonNull(workerId, "workerId must not be null");
            this.leaseGeneration = leaseGeneration;
            this.leaseDurationSeconds = leaseDurationSeconds;
        }

        public UUID executionId() {
            return executionId;
        }

        public String workerId() {
            return workerId;
        }

        public long leaseGeneration() {
            return leaseGeneration;
        }

        public long leaseDurationSeconds() {
            return leaseDurationSeconds;
        }

        public boolean isOwnershipLost() {
            return ownershipLost.get();
        }

        public void markOwnershipLost() {
            ownershipLost.set(true);
        }
    }
}
