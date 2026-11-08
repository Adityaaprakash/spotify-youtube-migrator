package com.spotifyyoutube.migrator.migration.reliability.scheduling;

import com.spotifyyoutube.migrator.migration.async.AsyncMigrationConfig;
import com.spotifyyoutube.migrator.migration.async.MigrationBackgroundWorker;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Two-phase durable migration scheduler and post-commit worker dispatcher (Phase 13D-B and 13D-C).
 * <p>
 * Implements strict two-phase scheduling:
 * <ol>
 *   <li><b>Phase 1 (Atomic Claim):</b> In an isolated database transaction, discovers and claims the next
 *       eligible execution using PostgreSQL {@code FOR UPDATE SKIP LOCKED}, increments monotonic fencing generation,
 *       and establishes worker lease ownership. The claim transaction commits before dispatch.</li>
 *   <li><b>Phase 2 (Worker Dispatch):</b> Dispatches claimed executions to the bounded worker executor outside
 *       any open database transaction. If the executor rejects submission (pool/queue saturation or shutdown),
 *       executes a conditional compensation transaction to safely release the lease.</li>
 * </ol>
 */
@Component
public class DurableMigrationScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DurableMigrationScheduler.class);

    private final MigrationExecutionRepository migrationExecutionRepository;
    private final MigrationBackgroundWorker migrationBackgroundWorker;
    private final TaskExecutor taskExecutor;
    private final PlatformTransactionManager transactionManager;
    private final DurableSchedulerProperties properties;
    private final String workerId;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DurableMigrationScheduler(
            MigrationExecutionRepository migrationExecutionRepository,
            MigrationBackgroundWorker migrationBackgroundWorker,
            @Qualifier(AsyncMigrationConfig.MIGRATION_TASK_EXECUTOR_BEAN) TaskExecutor taskExecutor,
            PlatformTransactionManager transactionManager,
            DurableSchedulerProperties properties
    ) {
        this.migrationExecutionRepository = Objects.requireNonNull(
                migrationExecutionRepository, "MigrationExecutionRepository must not be null");
        this.migrationBackgroundWorker = Objects.requireNonNull(
                migrationBackgroundWorker, "MigrationBackgroundWorker must not be null");
        this.taskExecutor = Objects.requireNonNull(
                taskExecutor, "TaskExecutor must not be null");
        this.transactionManager = Objects.requireNonNull(
                transactionManager, "PlatformTransactionManager must not be null");
        this.properties = Objects.requireNonNull(
                properties, "DurableSchedulerProperties must not be null");
        this.workerId = (properties.getWorkerIdPrefix() != null ? properties.getWorkerIdPrefix() : "scheduler-node-")
                + UUID.randomUUID();
    }

    /**
     * Unique worker node identifier for this scheduler instance.
     *
     * @return worker identifier string
     */
    public String getWorkerId() {
        return workerId;
    }

    /**
     * Periodic polling entry point invoked by Spring scheduler.
     * Claims and dispatches up to {@link DurableSchedulerProperties#getMaxClaimsPerPoll()} executions per cycle.
     */
    @Scheduled(fixedDelayString = "${migration.scheduler.poll-interval-ms:5000}")
    public void pollAndDispatch() {
        if (!isRunning() || !properties.isEnabled()) {
            return;
        }

        int maxClaims = properties.getMaxClaimsPerPoll();
        for (int i = 0; i < maxClaims; i++) {
            if (!isRunning()) {
                log.info("Scheduler shutdown initiated. Aborting remaining poll claims.");
                break;
            }

            if (!hasAvailableExecutorCapacity()) {
                log.debug("TaskExecutor queue capacity exhausted. Pausing scheduler claiming until next cycle.");
                break;
            }

            Optional<ClaimedExecution> claimedOpt = claimNextExecution();
            if (claimedOpt.isEmpty()) {
                // No eligible executions waiting; yield poll cycle
                break;
            }

            ClaimedExecution claim = claimedOpt.get();
            boolean dispatched = dispatchClaimedExecution(claim);
            if (!dispatched) {
                // Saturated or rejected; pause claiming in this cycle
                break;
            }
        }
    }

    /**
     * Phase 1: Atomically claims the next eligible execution in a short, dedicated database transaction.
     *
     * @return optional containing claimed execution and fencing generation, or empty if none eligible
     */
    public Optional<ClaimedExecution> claimNextExecution() {
        if (!isRunning() || !properties.isEnabled()) {
            return Optional.empty();
        }

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        return txTemplate.execute(status -> {
            Optional<ClaimedExecution> claim = migrationExecutionRepository.claimNextEligibleExecution(
                    workerId,
                    properties.getLeaseDurationSeconds()
            );
            claim.ifPresent(c -> log.info("Worker '{}' claimed execution {} with lease generation {}",
                    workerId, c.executionId(), c.leaseGeneration()));
            return claim;
        });
    }

    /**
     * Phase 2: Dispatches a previously claimed execution to the bounded worker pool.
     * Must be invoked strictly after the claim transaction has committed.
     *
     * @param claim claimed execution details
     * @return true if successfully dispatched, false if rejected and compensated
     */
    public boolean dispatchClaimedExecution(ClaimedExecution claim) {
        if (claim == null) {
            return false;
        }

        try {
            taskExecutor.execute(() -> {
                migrationBackgroundWorker.processExecution(
                        claim.executionId(),
                        claim.userId(),
                        claim.leaseGeneration()
                );
            });
            log.info("Dispatched execution {} (generation {}) to worker pool",
                    claim.executionId(), claim.leaseGeneration());
            return true;
        } catch (TaskRejectedException ex) {
            log.warn("Worker pool saturated. Dispatch rejected for execution {} (generation {}): {}. "
                    + "Triggering compensation.", claim.executionId(), claim.leaseGeneration(), ex.getMessage());
            compensateRejection(claim);
            return false;
        } catch (Exception ex) {
            log.error("Unexpected error dispatching execution {} (generation {}). Compensating claim: {}",
                    claim.executionId(), claim.leaseGeneration(), ex.getMessage(), ex);
            compensateRejection(claim);
            throw ex;
        }
    }

    /**
     * Compensates an un-dispatchable claimed execution in a short, dedicated transaction.
     * Conditionally resets execution status back to QUEUED and sets deferred retry attempt,
     * guarded by worker ID and fencing generation.
     *
     * @param claim the claim that could not be dispatched
     * @return true if compensation updated the database, false if lease was already lost or mutated
     */
    public boolean compensateRejection(ClaimedExecution claim) {
        if (claim == null) {
            return false;
        }

        Instant nextAttemptAt = Instant.now().plusSeconds(properties.getRejectionBackoffSeconds());

        try {
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            Integer rowsUpdated = txTemplate.execute(status ->
                    migrationExecutionRepository.compensateClaimRejection(
                            claim.executionId(),
                            claim.workerId(),
                            claim.leaseGeneration(),
                            nextAttemptAt
                    )
            );

            if (rowsUpdated != null && rowsUpdated == 1) {
                log.info("Successfully compensated rejected claim for execution {} (gen {}). Next attempt at {}",
                        claim.executionId(), claim.leaseGeneration(), nextAttemptAt);
                return true;
            } else {
                log.warn("Compensation affected 0 rows for execution {} (gen {}). "
                                + "Lease was likely taken over or expired.",
                        claim.executionId(), claim.leaseGeneration());
                return false;
            }
        } catch (Exception ex) {
            log.error("Failed to compensate rejected claim for execution {} (gen {}). "
                            + "Database error: {}. Expiry recovery will recover execution.",
                    claim.executionId(), claim.leaseGeneration(), ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * Inspects available executor queue capacity to prevent claiming when the executor is saturated.
     *
     * @return true if executor can accept tasks, false if queue is full
     */
    public boolean hasAvailableExecutorCapacity() {
        if (taskExecutor instanceof ThreadPoolTaskExecutor tpte) {
            try {
                return tpte.getThreadPoolExecutor().getQueue().remainingCapacity() > 0;
            } catch (Exception ex) {
                log.debug("Unable to inspect executor queue capacity, assuming available: {}", ex.getMessage());
                return true;
            }
        }
        return true;
    }

    // --- SmartLifecycle Implementation ---

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("DurableMigrationScheduler started with worker ID '{}'", workerId);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("DurableMigrationScheduler stopped for worker ID '{}'", workerId);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // Start after database and executors are initialized
        return 0;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
