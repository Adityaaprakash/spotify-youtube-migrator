package com.spotifyyoutube.migrator.identity.repository;

import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OAuthConnectionRepository extends JpaRepository<OAuthConnection, UUID> {
    Optional<OAuthConnection> findByUserIdAndProvider(UUID userId, OAuthProvider provider);
    Optional<OAuthConnection> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);
}
