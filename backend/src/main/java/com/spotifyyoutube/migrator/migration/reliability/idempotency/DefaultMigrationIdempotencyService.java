package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Production implementation of {@link MigrationIdempotencyService} (Phase 13E).
 * <p>
 * Manages atomic persistence of idempotency records, safe replay detection,
 * terminal-only retention updates, and bounded sweeper cleanup.
 */
@Service
public class DefaultMigrationIdempotencyService implements MigrationIdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMigrationIdempotencyService.class);

    public static final Duration DEFAULT_RETENTION = Duration.ofHours(72);
    public static final int DEFAULT_CLEANUP_BATCH_SIZE = 500;

    private final MigrationIdempotencyRepository repository;
    private final TransactionTemplate requiresNewTemplate;
    private final Clock clock;

    @Autowired
    public DefaultMigrationIdempotencyService(
            MigrationIdempotencyRepository repository,
            PlatformTransactionManager transactionManager,
            @Autowired(required = false) Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "MigrationIdempotencyRepository must not be null");
        Objects.requireNonNull(transactionManager, "PlatformTransactionManager must not be null");
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    public Optional<MigrationIdempotencyRecord> findExistingRecord(UUID userId, String idempotencyKey) {
        if (userId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey.trim());
    }

    @Override
    public MigrationIdempotencyRecord recordSubmission(
            UUID userId,
            String idempotencyKey,
            String requestFingerprint,
            UUID executionId,
            int responseStatus,
            String responsePayload
    ) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(responsePayload, "responsePayload must not be null");

        String cleanKey = idempotencyKey.trim();

        // 1. Check if record already exists before attempting insert
        Optional<MigrationIdempotencyRecord> existingOpt = repository.findByUserIdAndIdempotencyKey(userId, cleanKey);
        if (existingOpt.isPresent()) {
            return handleExistingRecord(existingOpt.get(), requestFingerprint, cleanKey);
        }

        // 2. Attempt atomic insertion in an isolated transaction to handle race conditions
        try {
            return requiresNewTemplate.execute(status -> {
                MigrationIdempotencyRecord record = MigrationIdempotencyRecord.createActive(
                        userId,
                        cleanKey,
                        requestFingerprint,
                        executionId,
                        responseStatus,
                        responsePayload
                );
                return repository.saveAndFlush(record);
            });
        } catch (DataIntegrityViolationException ex) {
            log.info("Concurrent key collision detected for user {} on idempotency key. Retrieving winner.", userId);
            // Race condition: concurrent submission committed between find and save
            MigrationIdempotencyRecord winner = repository.findByUserIdAndIdempotencyKey(userId, cleanKey)
                    .orElseThrow(() -> ex);
            return handleExistingRecord(winner, requestFingerprint, cleanKey);
        }
    }

    private MigrationIdempotencyRecord handleExistingRecord(
            MigrationIdempotencyRecord existing,
            String requestFingerprint,
            String idempotencyKey
    ) {
        if (!existing.getRequestFingerprint().equals(requestFingerprint)) {
            log.warn("Idempotency fingerprint mismatch for key {}: existing={}, incoming={}",
                    idempotencyKey, existing.getRequestFingerprint(), requestFingerprint);
            throw new IdempotencyFingerprintMismatchException(String.format(
                    "Idempotency key '%s' was previously used with a different request payload",
                    idempotencyKey
            ));
        }
        log.info("Matching idempotency replay found for key {} -> replaying execution {}",
                idempotencyKey, existing.getExecutionId());
        return existing;
    }

    @Override
    @Transactional
    public int markExecutionTerminal(UUID executionId, Instant terminalTransitionTime) {
        if (executionId == null) {
            return 0;
        }
        Instant baseTime = terminalTransitionTime != null ? terminalTransitionTime : clock.instant();
        Instant expiresAt = baseTime.plus(DEFAULT_RETENTION);

        int updated = repository.setTerminalExpiry(executionId, expiresAt);
        if (updated > 0) {
            log.info("Set terminal retention (expires at {}) for {} idempotency records on execution {}",
                    expiresAt, updated, executionId);
        }
        return updated;
    }

    @Override
    @Transactional
    @Scheduled(fixedDelayString = "${migration.idempotency.cleanup-interval-ms:3600000}")
    public int cleanupExpiredRecords() {
        try {
            int deleted = repository.deleteExpiredDatabaseTime(DEFAULT_CLEANUP_BATCH_SIZE);
            if (deleted > 0) {
                log.info("Idempotency cleanup sweeper purged {} expired records", deleted);
            }
            return deleted;
        } catch (Exception ex) {
            log.error("Error during scheduled idempotency records cleanup: {}", ex.getMessage(), ex);
            return 0;
        }
    }

    @Override
    @Transactional
    public int cleanupExpiredRecords(Instant cutoff) {
        if (cutoff == null) {
            return 0;
        }
        return repository.deleteExpiredBefore(cutoff);
    }
}
