package com.spotifyyoutube.migrator.migration.review.repository;

import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewTrackRepository extends JpaRepository<ReviewTrack, UUID> {

    List<ReviewTrack> findBySessionIdOrderByPositionAsc(UUID sessionId);

    Optional<ReviewTrack> findBySessionIdAndPosition(UUID sessionId, int position);

    Optional<ReviewTrack> findBySessionIdAndSourceTrackId(UUID sessionId, String sourceTrackId);
}
