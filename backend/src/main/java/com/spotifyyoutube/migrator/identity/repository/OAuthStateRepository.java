package com.spotifyyoutube.migrator.identity.repository;

import com.spotifyyoutube.migrator.identity.domain.OAuthState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OAuthStateRepository extends JpaRepository<OAuthState, String> {
    Optional<OAuthState> findByState(String state);
}
