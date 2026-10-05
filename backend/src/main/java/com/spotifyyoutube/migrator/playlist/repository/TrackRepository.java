package com.spotifyyoutube.migrator.playlist.repository;

import com.spotifyyoutube.migrator.playlist.domain.Track;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TrackRepository extends JpaRepository<Track, UUID> {
    List<Track> findByPlaylistId(UUID playlistId);
}
