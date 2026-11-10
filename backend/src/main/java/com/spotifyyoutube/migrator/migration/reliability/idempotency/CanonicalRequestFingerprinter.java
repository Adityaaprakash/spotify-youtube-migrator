package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic, versioned canonical request fingerprinter (Phase 13E).
 * <p>
 * Computes a SHA-256 digest with the "v1:" version prefix over canonical request inputs:
 * {@code v1:SHA-256(user_id \u001F source_id \u001F target_name \u001F sorted_track_hashes)}.
 * <p>
 * Guarantees:
 * <ul>
 *   <li>Independent of JSON serialization and property ordering</li>
 *   <li>Unambiguous delimiter boundaries using ASCII Unit Separator (\u001F) and Record Separator (\u001E)</li>
 *   <li>Unicode NFC normalization for textual inputs ensuring NFC and NFD produce identical fingerprints</li>
 *   <li>Preserves duplicate track occurrences during lexicographic track sorting</li>
 *   <li>Deterministic UTF-8 encoding for multilingual inputs</li>
 *   <li>Excludes volatile timestamps, execution IDs, and random request tokens</li>
 * </ul>
 */
@Component
public class CanonicalRequestFingerprinter {

    public static final String VERSION_PREFIX = "v1:";
    public static final String FIELD_DELIMITER = "\u001F";
    public static final String TRACK_DELIMITER = "\u001E";

    /**
     * Computes the canonical fingerprint for the given parameters.
     *
     * @param userId             authenticated user identifier
     * @param sourcePlaylistId   source playlist identifier
     * @param targetPlaylistName destination playlist title or name
     * @param trackHashes        track identifiers or hashes (order-insensitive; duplicates preserved)
     * @return versioned fingerprint string, e.g. {@code "v1:a3b5..."}
     */
    public String calculateFingerprint(
            UUID userId,
            String sourcePlaylistId,
            String targetPlaylistName,
            List<String> trackHashes
    ) {
        return calculateFingerprint(new CanonicalMigrationRequest(
                userId, sourcePlaylistId, targetPlaylistName, trackHashes
        ));
    }

    /**
     * Computes the canonical fingerprint for a canonical migration request.
     * Applies Unicode NFC normalization to textual inputs to guarantee equivalence.
     *
     * @param request validated canonical request representation
     * @return versioned fingerprint string
     */
    public String calculateFingerprint(CanonicalMigrationRequest request) {
        Objects.requireNonNull(request, "CanonicalMigrationRequest must not be null");

        List<String> sortedTracks = request.trackHashes().stream()
                .filter(Objects::nonNull)
                .map(t -> Normalizer.normalize(t.trim(), Normalizer.Form.NFC))
                .sorted()
                .toList();

        String joinedTracks = String.join(TRACK_DELIMITER, sortedTracks);

        String normalizedSourceId = Normalizer.normalize(request.sourcePlaylistId().trim(), Normalizer.Form.NFC);
        String normalizedTargetName = Normalizer.normalize(request.targetPlaylistName().trim(), Normalizer.Form.NFC);

        String canonicalString = request.userId().toString().trim()
                + FIELD_DELIMITER
                + normalizedSourceId
                + FIELD_DELIMITER
                + normalizedTargetName
                + FIELD_DELIMITER
                + joinedTracks;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalString.getBytes(StandardCharsets.UTF_8));
            return VERSION_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in current JVM", e);
        }
    }

    /**
     * Converts a validated {@link ApprovedMigrationContext} into a canonical migration request.
     *
     * @param context validated approved migration context
     * @return canonical migration request representation
     */
    public CanonicalMigrationRequest fromApprovedContext(ApprovedMigrationContext context) {
        Objects.requireNonNull(context, "ApprovedMigrationContext must not be null");

        List<String> trackHashes = context.approvedTracks().stream()
                .map(this::computeTrackHash)
                .toList();

        return new CanonicalMigrationRequest(
                context.userId(),
                context.sourcePlaylistId(),
                context.targetPlaylistTitle(),
                trackHashes
        );
    }

    private String computeTrackHash(ApprovedTrack track) {
        String src = track.sourceTrackId() != null ? track.sourceTrackId() : "";
        String dest = track.destinationTrackId() != null ? track.destinationTrackId() : "";
        return src + ":" + dest;
    }

    /**
     * Resolves a canonical migration request from an incoming JSON payload and repository context.
     *
     * @param userId                  authenticated user identifier
     * @param payload                 parsed JSON request body
     * @param reviewSessionRepository review session repository for approved migration resolution
     * @return canonical migration request representation
     */
    public CanonicalMigrationRequest resolveFromPayload(
            UUID userId,
            JsonNode payload,
            ReviewSessionRepository reviewSessionRepository
    ) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");

        // Case 1: Approved Migration (has reviewSessionId or sessionId)
        String sessionStr = null;
        if (payload.hasNonNull("reviewSessionId")) {
            sessionStr = payload.get("reviewSessionId").asText();
        } else if (payload.hasNonNull("sessionId")) {
            sessionStr = payload.get("sessionId").asText();
        }

        if (sessionStr != null && !sessionStr.isBlank()) {
            UUID sessionId = null;
            try {
                sessionId = UUID.fromString(sessionStr);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed UUID string
            }

            if (sessionId != null && reviewSessionRepository != null) {
                Optional<ReviewSession> sessionOpt = reviewSessionRepository.findById(sessionId);
                if (sessionOpt.isPresent()) {
                    ReviewSession session = sessionOpt.get();
                    String sourceId = session.getSourcePlaylistId();
                    String targetTitle = (payload.hasNonNull("targetPlaylistTitle")
                            && !payload.get("targetPlaylistTitle").asText().isBlank())
                            ? payload.get("targetPlaylistTitle").asText().trim()
                            : session.getSourcePlaylistTitle();

                    List<String> trackHashes = session.getTracks().stream()
                            .filter(t -> !t.isExcluded())
                            .map(t -> {
                                String dest = t.getSelectedCandidateId() != null
                                        ? t.getSelectedCandidateId()
                                        : (t.getOriginalCandidateId() != null ? t.getOriginalCandidateId() : "");
                                return t.getSourceTrackId() + ":" + dest;
                            })
                            .toList();

                    return new CanonicalMigrationRequest(userId, sourceId, targetTitle, trackHashes);
                }
            }

            // Fallback if session record not found yet in database
            String sourceId = payload.hasNonNull("sourcePlaylistId")
                    ? payload.get("sourcePlaylistId").asText()
                    : sessionStr;
            String targetTitle = payload.hasNonNull("targetPlaylistTitle")
                    ? payload.get("targetPlaylistTitle").asText().trim()
                    : "Migrated Playlist";
            return new CanonicalMigrationRequest(userId, sourceId, targetTitle, List.of(sessionStr));
        }

        // Case 2: Direct or legacy payload (sourcePlaylistId)
        String sourceId = payload.hasNonNull("sourcePlaylistId")
                ? payload.get("sourcePlaylistId").asText().trim()
                : "default-source";
        String targetTitle = payload.hasNonNull("targetPlaylistTitle")
                ? payload.get("targetPlaylistTitle").asText().trim()
                : sourceId;

        List<String> trackHashes = new ArrayList<>();
        if (payload.has("trackHashes") && payload.get("trackHashes").isArray()) {
            for (JsonNode n : payload.get("trackHashes")) {
                if (n != null && !n.isNull()) {
                    trackHashes.add(n.asText());
                }
            }
        }

        return new CanonicalMigrationRequest(userId, sourceId, targetTitle, trackHashes);
    }
}
