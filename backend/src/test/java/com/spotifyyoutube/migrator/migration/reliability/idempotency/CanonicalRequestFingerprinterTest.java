package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CanonicalRequestFingerprinter Unit Tests (Phase 13E)")
class CanonicalRequestFingerprinterTest {

    private CanonicalRequestFingerprinter fingerprinter;
    private UUID userId;

    @BeforeEach
    void setUp() {
        fingerprinter = new CanonicalRequestFingerprinter();
        userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    }

    @Nested
    @DisplayName("Fingerprint Correctness & Determinism")
    class CorrectnessAndDeterminismTests {

        @Test
        @DisplayName("Same logical request produces identical fingerprint regardless of track insertion order")
        void sameLogicalRequest_producesIdenticalFingerprint() {
            String fp1 = fingerprinter.calculateFingerprint(
                    userId, "spotify:playlist:1", "My Chill Mix",
                    List.of("trackB", "trackA", "trackC")
            );
            String fp2 = fingerprinter.calculateFingerprint(
                    userId, "spotify:playlist:1", "My Chill Mix",
                    List.of("trackC", "trackA", "trackB")
            );

            assertThat(fp1).isEqualTo(fp2);
        }

        @Test
        @DisplayName("Different source playlist produces different fingerprint")
        void differentSourcePlaylist_producesDifferentFingerprint() {
            String fp1 = fingerprinter.calculateFingerprint(
                    userId, "spotify:playlist:alpha", "Workout Tracks",
                    List.of("track1", "track2")
            );
            String fp2 = fingerprinter.calculateFingerprint(
                    userId, "spotify:playlist:beta", "Workout Tracks",
                    List.of("track1", "track2")
            );

            assertThat(fp1).isNotEqualTo(fp2);
        }

        @Test
        @DisplayName("Different target name produces different fingerprint")
        void differentTargetName_producesDifferentFingerprint() {
            String fp1 = fingerprinter.calculateFingerprint(
                    userId, "spotify:playlist:1", "Road Trip 2026",
                    List.of("track1")
            );
            String fp2 = fingerprinter.calculateFingerprint(
                    userId, "spotify:playlist:1", "Road Trip 2027",
                    List.of("track1")
            );

            assertThat(fp1).isNotEqualTo(fp2);
        }

        @Test
        @DisplayName("Different track contents produce different fingerprint")
        void differentTrackContents_producesDifferentFingerprint() {
            String fp1 = fingerprinter.calculateFingerprint(
                    userId, "spotify:playlist:1", "Favorites",
                    List.of("spotify:track:abc:yt:123")
            );
            String fp2 = fingerprinter.calculateFingerprint(
                    userId, "spotify:playlist:1", "Favorites",
                    List.of("spotify:track:xyz:yt:789")
            );

            assertThat(fp1).isNotEqualTo(fp2);
        }

        @Test
        @DisplayName("Track ordering follows defined lexicographical canonicalization rule")
        void trackOrdering_followsDefinedCanonicalizationRule() {
            String fpOriginal = fingerprinter.calculateFingerprint(
                    userId, "src-1", "Target",
                    List.of("zebra", "apple", "mango")
            );
            String fpPreSorted = fingerprinter.calculateFingerprint(
                    userId, "src-1", "Target",
                    List.of("apple", "mango", "zebra")
            );

            assertThat(fpOriginal).isEqualTo(fpPreSorted);
        }

        @Test
        @DisplayName("Duplicate track occurrences are preserved during canonicalization")
        void duplicateTrackOccurrences_arePreserved() {
            // Intentional duplicates in playlist (e.g. repeated track) must alter the fingerprint
            String fpWithDuplicate = fingerprinter.calculateFingerprint(
                    userId, "src-1", "Target",
                    List.of("track1", "track1", "track2")
            );
            String fpUniqueOnly = fingerprinter.calculateFingerprint(
                    userId, "src-1", "Target",
                    List.of("track1", "track2")
            );

            assertThat(fpWithDuplicate).isNotEqualTo(fpUniqueOnly);
        }

        @Test
        @DisplayName("Unicode and multilingual input behaves deterministically")
        void unicodeInput_behavesDeterministically() {
            String title = "日本語のプレイリスト 🎵 Café del Mar 🚀";
            String fp1 = fingerprinter.calculateFingerprint(
                    userId, "src:unicode", title,
                    List.of("トラック:1", "piste:2")
            );
            String fp2 = fingerprinter.calculateFingerprint(
                    userId, "src:unicode", title,
                    List.of("トラック:1", "piste:2")
            );

            assertThat(fp1).isEqualTo(fp2);
            assertThat(fp1).startsWith("v1:");
        }

        @Test
        @DisplayName("Version prefix is correct and SHA-256 output is 64 hex characters (total length 67)")
        void versionPrefix_isCorrect() {
            String fp = fingerprinter.calculateFingerprint(
                    userId, "source-id", "target-name", List.of("t1")
            );

            assertThat(fp).startsWith(CanonicalRequestFingerprinter.VERSION_PREFIX);
            assertThat(fp).hasSize(3 + 64);
            assertThat(fp.substring(3)).matches("^[a-f0-9]{64}$");
        }
    }

    @Nested
    @DisplayName("Validation & Boundary Tests")
    class ValidationAndBoundaryTests {

        @Test
        @DisplayName("Null userId throws NullPointerException")
        void nullUserId_throwsNullPointerException() {
            assertThatThrownBy(() -> fingerprinter.calculateFingerprint(
                    null, "src", "target", List.of()
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Null or blank source playlist throws validation exception")
        void nullOrBlankSourcePlaylist_throwsValidationException() {
            assertThatThrownBy(() -> fingerprinter.calculateFingerprint(
                    userId, null, "target", List.of()
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> fingerprinter.calculateFingerprint(
                    userId, "   ", "target", List.of()
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Null or blank target playlist name throws validation exception")
        void nullOrBlankTargetName_throwsValidationException() {
            assertThatThrownBy(() -> fingerprinter.calculateFingerprint(
                    userId, "src", null, List.of()
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> fingerprinter.calculateFingerprint(
                    userId, "src", "   ", List.of()
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Null track list is safely handled as empty list")
        void nullTrackList_handledAsEmpty() {
            String fp = fingerprinter.calculateFingerprint(
                    userId, "src", "target", null
            );

            assertThat(fp).startsWith("v1:");
        }
    }
}
