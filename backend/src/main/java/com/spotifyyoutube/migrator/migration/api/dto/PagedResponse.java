package com.spotifyyoutube.migrator.migration.api.dto;

import org.springframework.data.domain.Page;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Standardized presentation envelope for paginated collections (Phase 14C).
 *
 * @param <T> element type
 * @param content page elements
 * @param page 0-indexed page number
 * @param size requested page size
 * @param totalElements total elements across all pages
 * @param totalPages total number of available pages
 * @param first true if this is the first page
 * @param last true if this is the last page
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public PagedResponse {
        content = content != null ? Collections.unmodifiableList(content) : Collections.emptyList();
    }

    /**
     * Constructs a {@link PagedResponse} from a Spring Data {@link Page}.
     */
    public static <T> PagedResponse<T> from(Page<T> springPage) {
        if (springPage == null) {
            return new PagedResponse<>(Collections.emptyList(), 0, 0, 0L, 0, true, true);
        }
        return new PagedResponse<>(
                springPage.getContent(),
                springPage.getNumber(),
                springPage.getSize(),
                springPage.getTotalElements(),
                springPage.getTotalPages(),
                springPage.isFirst(),
                springPage.isLast()
        );
    }

    /**
     * Constructs a {@link PagedResponse} from a Spring Data {@link Page} applying a mapping function to content.
     */
    public static <S, T> PagedResponse<T> from(Page<S> springPage, Function<S, T> mapper) {
        if (springPage == null) {
            return new PagedResponse<>(Collections.emptyList(), 0, 0, 0L, 0, true, true);
        }
        List<T> mapped = springPage.getContent().stream()
                .map(mapper)
                .toList();
        return new PagedResponse<>(
                mapped,
                springPage.getNumber(),
                springPage.getSize(),
                springPage.getTotalElements(),
                springPage.getTotalPages(),
                springPage.isFirst(),
                springPage.isLast()
        );
    }
}
