package com.spotifyyoutube.migrator.migration.review.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.api.dto.DestinationCandidateResponse;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import com.spotifyyoutube.migrator.migration.review.api.dto.CandidateSelectionDto;
import com.spotifyyoutube.migrator.migration.review.api.dto.CreateReviewSessionRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.ExcludeTrackRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewCandidateResponse;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewCandidateSearchApiRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewCandidateSearchResultResponse;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewDecisionType;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewProgressSummaryResponse;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewSessionResponse;
import com.spotifyyoutube.migrator.migration.review.api.dto.ReviewTrackResponse;
import com.spotifyyoutube.migrator.migration.review.api.dto.SelectCandidateRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.SubmitReviewDecisionRequest;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrackStatus;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Review API DTO Serialization & Validation Tests")
public class ReviewApiSerializationTest {

    private ObjectMapper objectMapper;
    private Validator validator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Nested
    @DisplayName("CreateReviewSessionRequest Serialization & Aliases")
    class CreateRequestTests {

        @Test
        @DisplayName("Deserializes with sourcePlaylistId field")
        void deserializeWithSourcePlaylistId() throws Exception {
            String json = "{\"sourcePlaylistId\":\"spotify-101\",\"deduplicate\":false}";
            CreateReviewSessionRequest req = objectMapper.readValue(json, CreateReviewSessionRequest.class);
            assertThat(req.sourcePlaylistId()).isEqualTo("spotify-101");
            assertThat(req.isDeduplicate()).isFalse();
        }

        @Test
        @DisplayName("Deserializes with spotifyPlaylistId alias")
        void deserializeWithSpotifyPlaylistIdAlias() throws Exception {
            String json = "{\"spotifyPlaylistId\":\"spotify-202\"}";
            CreateReviewSessionRequest req = objectMapper.readValue(json, CreateReviewSessionRequest.class);
            assertThat(req.sourcePlaylistId()).isEqualTo("spotify-202");
            assertThat(req.isDeduplicate()).isTrue();
        }

        @Test
        @DisplayName("Deserializes with playlistId alias")
        void deserializeWithPlaylistIdAlias() throws Exception {
            String json = "{\"playlistId\":\"spotify-303\"}";
            CreateReviewSessionRequest req = objectMapper.readValue(json, CreateReviewSessionRequest.class);
            assertThat(req.sourcePlaylistId()).isEqualTo("spotify-303");
            assertThat(req.isDeduplicate()).isTrue();
        }

        @Test
        @DisplayName("Validates blank sourcePlaylistId")
        void validatesBlankSourcePlaylistId() {
            CreateReviewSessionRequest req = new CreateReviewSessionRequest("   ");
            var violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
            assertThat(violations.iterator().next().getMessage()).contains("cannot be blank");
        }
    }

    @Nested
    @DisplayName("ReviewSessionResponse Serialization")
    class SessionResponseSerializationTests {

        @Test
        @DisplayName("Serializes ReviewSessionResponse with nested progress")
        void serializeSessionResponse() throws Exception {
            UUID sessionId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            UUID planId = UUID.randomUUID();
            Instant now = Instant.parse("2026-10-29T10:00:00Z");

            ReviewProgressSummaryResponse progress = new ReviewProgressSummaryResponse(
                    10, 2, 6, 1, 1, 0, 2, false
            );
            ReviewSessionResponse resp = new ReviewSessionResponse(
                    sessionId, userId, "spotify-1", "Playlist Title", planId,
                    ReviewSessionStatus.IN_PROGRESS, 10, now, now, null, progress
            );

            String json = objectMapper.writeValueAsString(resp);
            assertThat(json).contains(sessionId.toString());
            assertThat(json).contains("spotify-1");
            assertThat(json).contains("Playlist Title");
            assertThat(json).contains("IN_PROGRESS");
            assertThat(json).contains("\"totalTracks\":10");
            assertThat(json).contains("\"eligibleForCompletion\":false");
            // Verify no entity fields or internal metadata leaked
            assertThat(json).doesNotContain("password");
            assertThat(json).doesNotContain("hibernateLazyInitializer");
        }
    }

    @Nested
    @DisplayName("ReviewTrackResponse Serialization")
    class TrackResponseSerializationTests {

        @Test
        @DisplayName("Serializes complete ReviewTrackResponse with both candidates")
        void serializeCompleteTrackResponse() throws Exception {
            UUID trackId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();

            ReviewCandidateResponse origCand = new ReviewCandidateResponse(
                    "yt-orig", "Original Video", "Original Artist", 240000L, Duration.ofSeconds(240)
            );
            ReviewCandidateResponse selCand = new ReviewCandidateResponse(
                    "yt-sel", "Selected Video", "Selected Artist", 245000L, Duration.ofSeconds(245)
            );

            ReviewTrackResponse resp = new ReviewTrackResponse(
                    trackId, sessionId, 0, "sp-1", "Song Title", "Song Artist",
                    PlanTrackClassification.REVIEW_REQUIRED, origCand,
                    0.82, 0.79, MatchDecision.REVIEW_REQUIRED, "Duration discrepancy",
                    ReviewTrackStatus.USER_SELECTED, ReviewDecisionSource.USER_SELECTED,
                    selCand, false, false, null, "User chose live version",
                    true, false
            );

            String json = objectMapper.writeValueAsString(resp);
            assertThat(json).contains("sp-1");
            assertThat(json).contains("yt-orig");
            assertThat(json).contains("yt-sel");
            assertThat(json).contains("REVIEW_REQUIRED");
            assertThat(json).contains("USER_SELECTED");
            assertThat(json).contains("\"approvedForMigration\":true");
            assertThat(json).contains("\"unresolved\":false");
        }

        @Test
        @DisplayName("Serializes track with null optional fields safely")
        void serializeTrackWithNullOptionals() throws Exception {
            UUID trackId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();

            ReviewTrackResponse resp = new ReviewTrackResponse(
                    trackId, sessionId, 1, "sp-2", "Unmatched Song", "Unknown Artist",
                    PlanTrackClassification.UNMATCHED, null,
                    null, null, null, null,
                    ReviewTrackStatus.AWAITING_REVIEW, ReviewDecisionSource.AUTOMATIC,
                    null, false, false, null, null,
                    false, true
            );

            String json = objectMapper.writeValueAsString(resp);
            assertThat(json).contains("sp-2");
            assertThat(json).contains("\"originalCandidate\":null");
            assertThat(json).contains("\"selectedCandidate\":null");
            assertThat(json).contains("\"approvedForMigration\":false");
            assertThat(json).contains("\"unresolved\":true");
        }
    }

    @Nested
    @DisplayName("CandidateSelectionDto Validation & Conversion")
    class CandidateSelectionTests {

        @Test
        @DisplayName("Converts valid DTO to DestinationTrackCandidate")
        void convertsValidDto() {
            CandidateSelectionDto dto = new CandidateSelectionDto(
                    "yt-valid", "Valid Title", "Artist", 180000L
            );
            var candidate = dto.toDomain();
            assertThat(candidate.id()).isEqualTo("yt-valid");
            assertThat(candidate.title()).isEqualTo("Valid Title");
            assertThat(candidate.duration()).isEqualTo(Duration.ofMillis(180000));
        }

        @Test
        @DisplayName("Rejects negative durationMs with IllegalArgumentException")
        void rejectsNegativeDurationMs() {
            assertThatThrownBy(() -> new CandidateSelectionDto("yt-id", "Title", "Artist", -500L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be negative");
        }

        @Test
        @DisplayName("Validates blank ID and blank title")
        void validatesBlankIdAndTitle() {
            CandidateSelectionDto dto = new CandidateSelectionDto("   ", "   ", "Artist", 1000L);
            var violations = validator.validate(dto);
            assertThat(violations).hasSize(2);
        }
    }

    @Nested
    @DisplayName("SubmitReviewDecisionRequest Validation")
    class SubmitDecisionValidationTests {

        @Test
        @DisplayName("Rejects SELECT_CANDIDATE when candidate is null")
        void rejectsSelectCandidateWithoutCandidate() {
            assertThatThrownBy(() -> new SubmitReviewDecisionRequest(ReviewDecisionType.SELECT_CANDIDATE, null, "notes"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("candidate is required");
        }

        @Test
        @DisplayName("Accepts SELECT_CANDIDATE when valid candidate is provided")
        void acceptsSelectCandidateWithCandidate() {
            CandidateSelectionDto cand = new CandidateSelectionDto("yt-1", "Title", "Artist", 120000L);
            SubmitReviewDecisionRequest req = SubmitReviewDecisionRequest.select(cand, "notes");
            assertThat(req.decisionType()).isEqualTo(ReviewDecisionType.SELECT_CANDIDATE);
            assertThat(req.candidate()).isNotNull();
        }

        @Test
        @DisplayName("Validates null decisionType")
        void validatesNullDecisionType() {
            SubmitReviewDecisionRequest req = new SubmitReviewDecisionRequest(null, null, "notes");
            var violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("Serializes and deserializes SelectCandidateRequest")
        void selectCandidateRequestSerialization() throws Exception {
            CandidateSelectionDto cand = new CandidateSelectionDto("yt-id-1", "Video Title", "Artist", 180000L);
            SelectCandidateRequest req = new SelectCandidateRequest(cand, "Reviewer preference");
            String json = objectMapper.writeValueAsString(req);
            SelectCandidateRequest deserialized = objectMapper.readValue(json, SelectCandidateRequest.class);
            assertThat(deserialized.candidate().id()).isEqualTo("yt-id-1");
            assertThat(deserialized.notes()).isEqualTo("Reviewer preference");
        }

        @Test
        @DisplayName("Serializes and deserializes ExcludeTrackRequest")
        void excludeTrackRequestSerialization() throws Exception {
            ExcludeTrackRequest req = new ExcludeTrackRequest("Live recording excluded");
            String json = objectMapper.writeValueAsString(req);
            ExcludeTrackRequest deserialized = objectMapper.readValue(json, ExcludeTrackRequest.class);
            assertThat(deserialized.reason()).isEqualTo("Live recording excluded");
        }
    }

    @Nested
    @DisplayName("ReviewCandidateSearchApiRequest Validation")
    class SearchApiRequestTests {

        @Test
        @DisplayName("Validates query length exceeding 500 characters")
        void validatesLongQuery() {
            String longQuery = "a".repeat(501);
            ReviewCandidateSearchApiRequest req = new ReviewCandidateSearchApiRequest(longQuery, 10);
            var violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
            assertThat(violations.iterator().next().getMessage()).contains("cannot exceed 500 characters");
        }

        @Test
        @DisplayName("Validates limit below 1")
        void validatesLimitBelowOne() {
            ReviewCandidateSearchApiRequest req = new ReviewCandidateSearchApiRequest("query", 0);
            var violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
            assertThat(violations.iterator().next().getMessage()).contains("at least 1");
        }

        @Test
        @DisplayName("Validates limit above 50")
        void validatesLimitAboveFifty() {
            ReviewCandidateSearchApiRequest req = new ReviewCandidateSearchApiRequest("query", 51);
            var violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
            assertThat(violations.iterator().next().getMessage()).contains("cannot exceed 50");
        }

        @Test
        @DisplayName("Accepts null query and null limit for automated default search")
        void acceptsDefaultNulls() {
            ReviewCandidateSearchApiRequest req = new ReviewCandidateSearchApiRequest(null, null);
            var violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }
    }

    @Nested
    @DisplayName("ReviewCandidateSearchResultResponse Serialization")
    class SearchResultResponseTests {

        @Test
        @DisplayName("Serializes search results with candidates list")
        void serializeSearchResultResponse() throws Exception {
            UUID sessionId = UUID.randomUUID();
            DestinationCandidateResponse cand = new DestinationCandidateResponse(
                    "yt-cand-1", "Song Title", "Artist", "Description",
                    "https://thumb.url", "2024-01-01", Duration.ofSeconds(180)
            );
            ReviewCandidateSearchResultResponse resp = new ReviewCandidateSearchResultResponse(
                    sessionId, 0, "sp-1", "Song Title", "Artist",
                    "Song Title Artist", List.of(cand), false, 1
            );

            String json = objectMapper.writeValueAsString(resp);
            assertThat(json).contains(sessionId.toString());
            assertThat(json).contains("yt-cand-1");
            assertThat(json).contains("Song Title");
            assertThat(json).contains("\"totalResults\":1");
            assertThat(json).contains("\"isCustomQuery\":false");
        }
    }
}
