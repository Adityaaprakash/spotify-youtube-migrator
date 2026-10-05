package com.spotifyyoutube.migrator.identity.repository;

import com.spotifyyoutube.migrator.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    Optional<User> findBySpotifyId(String spotifyId);
    Optional<User> findByYoutubeId(String youtubeId);
}
