package com.spotifyyoutube.migrator.migration.review.repository;

import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewSessionRepository extends JpaRepository<ReviewSession, UUID> {

    Optional<ReviewSession> findByIdAndUserId(UUID id, UUID userId);

    List<ReviewSession> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<ReviewSession> findByPlanId(UUID planId);
}
