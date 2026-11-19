package com.spotifyyoutube.migrator.common.metrics;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 18C: TagPolicy and Cardinality Protection Tests")
class TagPolicyTest {

    @Nested
    @DisplayName("Valid Tag Acceptance")
    class ValidTagTests {

        @Test
        @DisplayName("Accepts valid low-cardinality tags")
        void acceptsValidTags() {
            Tags tags = TagPolicy.tags(
                    TagKeys.PROVIDER, "spotify",
                    TagKeys.STATUS, "success",
                    TagKeys.OPERATION, "search",
                    TagKeys.CONFIDENCE, "high",
                    TagKeys.DECISION, "matched",
                    TagKeys.TRIGGER_MODE, "manual"
            );

            assertThat(tags).contains(
                    Tag.of(TagKeys.PROVIDER, "spotify"),
                    Tag.of(TagKeys.STATUS, "success")
            );
        }

        @Test
        @DisplayName("TagPolicy.tag() creates a single validated tag")
        void singleTagCreation() {
            Tags tag = TagPolicy.tag(TagKeys.PROVIDER, "youtube");
            assertThat(tag).containsExactly(Tag.of("provider", "youtube"));
        }

        @Test
        @DisplayName("TagPolicy.tags() handles empty input cleanly")
        void emptyTagsHandled() {
            assertThat(TagPolicy.tags()).isEqualTo(Tags.empty());
            assertThat(TagPolicy.tags((String[]) null)).isEqualTo(Tags.empty());
        }
    }

    @Nested
    @DisplayName("Prohibited Tag Key Rejection")
    class ProhibitedTagKeyTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "userId", "user_id", "playlistId", "playlist_id",
                "trackId", "track_id", "title", "artist", "url",
                "query", "requestId", "correlationId", "traceId",
                "executionId", "exceptionMessage", "errorMessage"
        })
        @DisplayName("Rejects high-cardinality tag keys")
        void rejectsProhibitedKeys(String key) {
            assertThatThrownBy(() -> TagPolicy.validateTag(key, "valid_value"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is prohibited due to high cardinality risk");
        }

        @Test
        @DisplayName("TagKeys.isProhibited returns false for null and permitted keys")
        void permittedKeysNotProhibited() {
            assertThat(TagKeys.isProhibited(null)).isFalse();
            assertThat(TagKeys.isProhibited(TagKeys.PROVIDER)).isFalse();
            assertThat(TagKeys.isProhibited(TagKeys.OPERATION)).isFalse();
            assertThat(TagKeys.isProhibited(TagKeys.STATUS)).isFalse();
        }
    }

    @Nested
    @DisplayName("Tag Key Format Validation")
    class KeyFormatTests {

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("Rejects null or blank tag keys")
        void rejectsBlankKeys(String key) {
            assertThatThrownBy(() -> TagPolicy.validateTag(key, "value"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Metric tag key must not be null or blank");

            assertThatThrownBy(() -> TagPolicy.validateTag(null, "value"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Metric tag key must not be null or blank");
        }

        @ParameterizedTest
        @ValueSource(strings = {"Provider Name", "provider/type", "provider:name", "PROVIDER"})
        @DisplayName("Rejects malformed tag keys that do not match [a-z0-9_.-]+")
        void rejectsMalformedKeys(String key) {
            assertThatThrownBy(() -> TagPolicy.validateTag(key, "value"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contains invalid characters");
        }
    }

    @Nested
    @DisplayName("Tag Value Safety and Cardinality Validation")
    class ValueFormatTests {

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("Rejects null or blank tag values")
        void rejectsBlankValues(String value) {
            assertThatThrownBy(() -> TagPolicy.validateTag("provider", value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null or blank");

            assertThatThrownBy(() -> TagPolicy.validateTag("provider", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null or blank");
        }

        @Test
        @DisplayName("Rejects tag values exceeding maximum allowed length")
        void rejectsOversizedValues() {
            String longValue = "a".repeat(TagPolicy.MAX_VALUE_LENGTH + 1);
            assertThatThrownBy(() -> TagPolicy.validateTag("provider", longValue))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum allowed length");
        }

        @Test
        @DisplayName("Rejects UUID tag values")
        void rejectsUuidValues() {
            String uuid = "550e8400-e29b-41d4-a716-446655440000";
            assertThatThrownBy(() -> TagPolicy.validateTag("provider", uuid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is a UUID, which causes high cardinality");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://example.com/api/tracks",
                "https://api.spotify.com/v1/playlists/123",
                "HTTPS://YOUTUBE.COM/WATCH?V=ABC"
        })
        @DisplayName("Rejects URL tag values")
        void rejectsUrlValues(String url) {
            assertThatThrownBy(() -> TagPolicy.validateTag("provider", url))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is a URL, which causes high cardinality");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "user@example.com",
                "test.user+tag@domain.co.uk"
        })
        @DisplayName("Rejects email address tag values")
        void rejectsEmailValues(String email) {
            assertThatThrownBy(() -> TagPolicy.validateTag("provider", email))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is an email address, which causes high cardinality");
        }

        @Test
        @DisplayName("Rejects odd number of key-value arguments")
        void rejectsOddArguments() {
            assertThatThrownBy(() -> TagPolicy.tags("key1", "val1", "key2"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be supplied in even pairs");
        }
    }
}
