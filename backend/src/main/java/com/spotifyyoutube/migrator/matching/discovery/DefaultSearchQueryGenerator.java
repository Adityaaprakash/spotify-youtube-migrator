package com.spotifyyoutube.migrator.matching.discovery;

import com.spotifyyoutube.migrator.matching.normalization.LivePerformanceDetails;
import com.spotifyyoutube.migrator.matching.normalization.MetadataToken;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RemasterDetails;
import com.spotifyyoutube.migrator.matching.normalization.TokenCategory;
import com.spotifyyoutube.migrator.matching.normalization.VariantType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Default deterministic implementation of {@link SearchQueryGenerator}.
 * Formulates tiered, bounded, and prioritized search queries targeted at YouTube candidate discovery
 * based on normalized track metadata and configured discovery options.
 */
@Component
public class DefaultSearchQueryGenerator implements SearchQueryGenerator {

    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern OUTER_BRACKET_PATTERN = Pattern.compile("^[\\[\\(\\{]+|[\\]\\)\\}]+$");

    private static final List<SearchQueryStrategy> CANONICAL_STRATEGY_ORDER = List.of(
            SearchQueryStrategy.PRIMARY_TITLE_ARTIST,
            SearchQueryStrategy.TITLE_ARTIST_FEATURED,
            SearchQueryStrategy.TITLE_ARTIST_ALBUM,
            SearchQueryStrategy.TITLE_ARTIST_VERSION,
            SearchQueryStrategy.RAW_TITLE_AND_ARTIST,
            SearchQueryStrategy.CLEAN_TITLE_ONLY
    );

    @Override
    public List<SearchQuery> generateQueries(NormalizedTrackMetadata trackMetadata, DiscoveryOptions options) {
        Objects.requireNonNull(trackMetadata, "trackMetadata must not be null");
        Objects.requireNonNull(options, "options must not be null");

        int maxQueries = options.maxQueries();
        Set<SearchQueryStrategy> enabled = options.enabledStrategies();

        List<SearchQuery> results = new ArrayList<>();
        Set<String> seenNormalizedQueries = new LinkedHashSet<>();

        for (SearchQueryStrategy strategy : CANONICAL_STRATEGY_ORDER) {
            if (results.size() >= maxQueries) {
                break;
            }

            if (!enabled.contains(strategy)) {
                continue;
            }

            String candidateText = buildQueryTextForStrategy(strategy, trackMetadata, enabled, results);
            if (candidateText == null || candidateText.isBlank()) {
                continue;
            }

            // Enforce length limit safely without throwing
            String boundedText = enforceMaxLength(candidateText);
            if (boundedText.isBlank()) {
                continue;
            }

            // Enforce duplicate suppression policy
            String deduplicationKey = normalizeForDeduplication(boundedText);
            if (seenNormalizedQueries.contains(deduplicationKey)) {
                continue;
            }

            seenNormalizedQueries.add(deduplicationKey);
            int priority = results.size() + 1;
            results.add(new SearchQuery(boundedText, strategy, priority));
        }

        return Collections.unmodifiableList(results);
    }

    private String buildQueryTextForStrategy(
            SearchQueryStrategy strategy,
            NormalizedTrackMetadata metadata,
            Set<SearchQueryStrategy> enabledStrategies,
            List<SearchQuery> currentQueries
    ) {
        String primaryArtist = resolvePrimaryArtist(metadata);
        String cleanTitle = resolveCleanTitle(metadata);

        return switch (strategy) {
            case PRIMARY_TITLE_ARTIST -> buildPrimaryTitleArtist(primaryArtist, cleanTitle);
            case TITLE_ARTIST_FEATURED -> buildTitleArtistFeatured(primaryArtist, cleanTitle, metadata.featuredArtists());
            case TITLE_ARTIST_ALBUM -> buildTitleArtistAlbum(primaryArtist, cleanTitle, metadata);
            case TITLE_ARTIST_VERSION -> buildTitleArtistVersion(primaryArtist, cleanTitle, metadata);
            case RAW_TITLE_AND_ARTIST -> buildRawTitleAndArtist(metadata, enabledStrategies, currentQueries);
            case CLEAN_TITLE_ONLY -> buildCleanTitleOnly(cleanTitle, primaryArtist, enabledStrategies, currentQueries);
        };
    }

    private String buildPrimaryTitleArtist(String primaryArtist, String cleanTitle) {
        if (primaryArtist.isBlank() || cleanTitle.isBlank()) {
            return null;
        }
        return primaryArtist + " - " + cleanTitle;
    }

    private String buildTitleArtistFeatured(String primaryArtist, String cleanTitle, List<String> featuredArtists) {
        if (primaryArtist.isBlank() || cleanTitle.isBlank() || featuredArtists.isEmpty()) {
            return null;
        }

        List<String> validFeatured = featuredArtists.stream()
                .filter(a -> a != null && !a.isBlank())
                .toList();

        if (validFeatured.isEmpty()) {
            return null;
        }

        String joinedFeatured = String.join(", ", validFeatured);
        return primaryArtist + " feat. " + joinedFeatured + " - " + cleanTitle;
    }

    private String buildTitleArtistAlbum(String primaryArtist, String cleanTitle, NormalizedTrackMetadata metadata) {
        if (primaryArtist.isBlank() || cleanTitle.isBlank()) {
            return null;
        }

        String album = resolveAlbum(metadata);
        if (album.isBlank()) {
            return null;
        }

        // Avoid appending duplicate album text if the album is identical to the clean title
        if (album.equalsIgnoreCase(cleanTitle)) {
            return null;
        }

        return primaryArtist + " - " + cleanTitle + " " + album;
    }

    private String buildTitleArtistVersion(String primaryArtist, String cleanTitle, NormalizedTrackMetadata metadata) {
        if (primaryArtist.isBlank() || cleanTitle.isBlank()) {
            return null;
        }

        String versionQualifier = resolveVersionQualifier(metadata);
        if (versionQualifier.isBlank()) {
            return null;
        }

        // Avoid duplicate version qualifier if cleanTitle already ends with it
        if (cleanTitle.toLowerCase(Locale.ROOT).endsWith(versionQualifier.toLowerCase(Locale.ROOT))) {
            return null;
        }

        return primaryArtist + " - " + cleanTitle + " " + versionQualifier;
    }

    private String buildRawTitleAndArtist(
            NormalizedTrackMetadata metadata,
            Set<SearchQueryStrategy> enabledStrategies,
            List<SearchQuery> currentQueries
    ) {
        RawTrackMetadata raw = metadata.rawMetadata();
        if (raw == null) {
            return null;
        }

        String rawTitle = raw.title() != null ? raw.title().trim() : "";
        if (rawTitle.isBlank()) {
            return null;
        }

        String rawArtist = resolveRawArtist(raw);
        if (rawArtist.isBlank()) {
            return null;
        }

        // Appropriate when:
        // 1. Raw title contains promotional markers or unknown annotations stripped during normalization
        // 2. Or no prior query could be generated (needed as fallback)
        // 3. Or RAW_TITLE_AND_ARTIST is explicitly the only enabled strategy
        boolean hasStrippedContent = (metadata.tokenizedTitle() != null && metadata.tokenizedTitle().isOfficial())
                || (metadata.tokenizedTitle() != null && !metadata.tokenizedTitle().unknownAnnotations().isEmpty());

        boolean isSoleEnabledStrategy = enabledStrategies.size() == 1
                && enabledStrategies.contains(SearchQueryStrategy.RAW_TITLE_AND_ARTIST);

        boolean neededAsFallback = currentQueries.isEmpty();

        if (hasStrippedContent || isSoleEnabledStrategy || neededAsFallback) {
            return rawArtist + " - " + rawTitle;
        }

        return null;
    }

    private String buildCleanTitleOnly(
            String cleanTitle,
            String primaryArtist,
            Set<SearchQueryStrategy> enabledStrategies,
            List<SearchQuery> currentQueries
    ) {
        if (cleanTitle.isBlank()) {
            return null;
        }

        // Appropriate when:
        // 1. Primary artist is absent or non-informative (e.g. soundtrack/various artists)
        // 2. Or no prior query could be generated
        // 3. Or CLEAN_TITLE_ONLY is explicitly the only enabled strategy
        boolean isObscureOrNoArtist = primaryArtist.isBlank()
                || primaryArtist.equalsIgnoreCase("various artists")
                || primaryArtist.equalsIgnoreCase("soundtrack")
                || primaryArtist.equalsIgnoreCase("original soundtrack");

        boolean isSoleEnabledStrategy = enabledStrategies.size() == 1
                && enabledStrategies.contains(SearchQueryStrategy.CLEAN_TITLE_ONLY);

        boolean neededAsFallback = currentQueries.isEmpty();

        if (isObscureOrNoArtist || isSoleEnabledStrategy || neededAsFallback) {
            return cleanTitle;
        }

        return null;
    }

    private String resolvePrimaryArtist(NormalizedTrackMetadata metadata) {
        String artist = metadata.primaryArtist();
        if (artist != null && !artist.isBlank()) {
            return artist.trim();
        }

        if (!metadata.normalizedArtists().isEmpty()) {
            String first = metadata.normalizedArtists().get(0);
            if (first != null && !first.isBlank()) {
                return first.trim();
            }
        }

        return "";
    }

    private String resolveCleanTitle(NormalizedTrackMetadata metadata) {
        String title = metadata.cleanTitle();
        if (title != null && !title.isBlank()) {
            return title.trim().toLowerCase(Locale.ROOT);
        }

        String normalized = metadata.normalizedTitle();
        if (normalized != null && !normalized.isBlank()) {
            return normalized.trim().toLowerCase(Locale.ROOT);
        }

        if (metadata.rawMetadata() != null && metadata.rawMetadata().title() != null) {
            return metadata.rawMetadata().title().trim().toLowerCase(Locale.ROOT);
        }

        return "";
    }

    private String resolveAlbum(NormalizedTrackMetadata metadata) {
        String album = metadata.normalizedAlbum();
        if (album != null && !album.isBlank()) {
            return album.trim().toLowerCase(Locale.ROOT);
        }

        if (metadata.rawMetadata() != null && metadata.rawMetadata().album() != null) {
            return metadata.rawMetadata().album().trim().toLowerCase(Locale.ROOT);
        }

        return "";
    }

    private String resolveRawArtist(RawTrackMetadata raw) {
        if (raw.artistCredit() != null && !raw.artistCredit().isBlank()) {
            return raw.artistCredit().trim();
        }

        if (raw.artists() != null && !raw.artists().isEmpty()) {
            String first = raw.artists().get(0);
            if (first != null && !first.isBlank()) {
                return first.trim();
            }
        }

        return "";
    }

    private String resolveVersionQualifier(NormalizedTrackMetadata metadata) {
        List<String> qualifiers = new ArrayList<>();

        // 1. Extract from recognized tokens if available
        if (metadata.tokenizedTitle() != null && metadata.tokenizedTitle().tokens() != null) {
            for (MetadataToken token : metadata.tokenizedTitle().tokens()) {
                if (isVersionTokenCategory(token.category())) {
                    String text = token.rawText();
                    if (text == null || text.isBlank()) {
                        text = token.value();
                    }
                    if (text != null && !text.isBlank()) {
                        String cleaned = cleanTokenText(text);
                        if (!cleaned.isBlank() && !qualifiers.contains(cleaned)) {
                            qualifiers.add(cleaned);
                        }
                    }
                }
            }
        }

        // 2. Supplemental details if not already captured
        LivePerformanceDetails live = metadata.liveDetails();
        if (live != null) {
            if (live.rawDescriptor() != null && !live.rawDescriptor().isBlank()) {
                String cleaned = cleanTokenText(live.rawDescriptor());
                if (!cleaned.isBlank() && !qualifiers.contains(cleaned)) {
                    qualifiers.add(cleaned);
                }
            } else if (live.venueOrEvent() != null && !live.venueOrEvent().isBlank()) {
                String cleaned = "live " + cleanTokenText(live.venueOrEvent());
                if (!qualifiers.contains(cleaned)) {
                    qualifiers.add(cleaned);
                }
            }
        }

        RemasterDetails remaster = metadata.remasterDetails();
        if (remaster != null) {
            if (remaster.rawDescriptor() != null && !remaster.rawDescriptor().isBlank()) {
                String cleaned = cleanTokenText(remaster.rawDescriptor());
                if (!cleaned.isBlank() && !qualifiers.contains(cleaned)) {
                    qualifiers.add(cleaned);
                }
            } else if (remaster.year() != null) {
                String cleaned = "remastered " + remaster.year();
                if (!qualifiers.contains(cleaned)) {
                    qualifiers.add(cleaned);
                }
            }
        }

        // 3. Fallback to VariantType enum names if tokens were empty
        if (qualifiers.isEmpty() && metadata.variants() != null) {
            for (VariantType variant : metadata.variants()) {
                String label = mapVariantToLabel(variant);
                if (!label.isBlank() && !qualifiers.contains(label)) {
                    qualifiers.add(label);
                }
            }
        }

        if (qualifiers.isEmpty()) {
            return "";
        }

        return String.join(" ", qualifiers).toLowerCase(Locale.ROOT);
    }

    private boolean isVersionTokenCategory(TokenCategory category) {
        return category == TokenCategory.PERFORMANCE
                || category == TokenCategory.REMASTER
                || category == TokenCategory.EDIT_OR_MIX
                || category == TokenCategory.DERIVATIVE_VERSION;
    }

    private String cleanTokenText(String text) {
        String stripped = OUTER_BRACKET_PATTERN.matcher(text.trim()).replaceAll("").trim();
        return MULTI_SPACE_PATTERN.matcher(stripped).replaceAll(" ").trim();
    }

    private String mapVariantToLabel(VariantType variant) {
        return switch (variant) {
            case REMASTER -> "remaster";
            case RADIO_EDIT -> "radio edit";
            case EXTENDED_MIX -> "extended mix";
            case ORIGINAL_MIX -> "original mix";
            case CLUB_MIX -> "club mix";
            case INSTRUMENTAL -> "instrumental";
            case DEMO -> "demo";
            case LIVE -> "live";
            case ACOUSTIC -> "acoustic";
            case UNPLUGGED -> "unplugged";
            case STUDIO -> "studio";
            case REMIX -> "remix";
            case COVER -> "cover";
            case KARAOKE -> "karaoke";
            case NIGHTCORE -> "nightcore";
            case SPED_UP -> "sped up";
            case SLOWED -> "slowed";
            case SLOWED_REVERB -> "slowed reverb";
            case REVERB -> "reverb";
        };
    }

    private String enforceMaxLength(String query) {
        String trimmed = query.trim();
        if (trimmed.length() <= SearchQuery.MAX_QUERY_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, SearchQuery.MAX_QUERY_LENGTH).trim();
    }

    private String normalizeForDeduplication(String query) {
        String collapsed = MULTI_SPACE_PATTERN.matcher(query.trim()).replaceAll(" ");
        return collapsed.toLowerCase(Locale.ROOT);
    }
}
