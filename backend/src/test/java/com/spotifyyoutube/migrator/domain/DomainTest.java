package com.spotifyyoutube.migrator.domain;

import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.playlist.domain.Playlist;
import com.spotifyyoutube.migrator.playlist.domain.Track;
import com.spotifyyoutube.migrator.migration.domain.MigrationJob;
import com.spotifyyoutube.migrator.migration.domain.MigrationStatus;
import com.spotifyyoutube.migrator.migration.domain.MigrationTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DomainTest {

    @Test
    public void testUserEntity() {
        User user = new User("test@domain.com");
        user.setSpotifyId("spotify-123");
        user.setYoutubeId("yt-123");
        
        assertEquals("test@domain.com", user.getEmail());
        assertEquals("spotify-123", user.getSpotifyId());
        assertEquals("yt-123", user.getYoutubeId());
    }

    @Test
    public void testTrackOccurrenceSemantics() {
        Playlist playlist = new Playlist();
        playlist.setName("My List");
        
        Track track1 = new Track();
        track1.setName("Song A");
        track1.setPlaylist(playlist);

        Track track2 = new Track();
        track2.setName("Song A"); // Duplicate song
        track2.setPlaylist(playlist);
        
        // Verifying ADR-009: Track is a playlist occurrence and simply holds a reference to a single Playlist.
        assertEquals(playlist, track1.getPlaylist());
        assertEquals(playlist, track2.getPlaylist());
    }

    @Test
    public void testMigrationJobDefaults() {
        MigrationJob job = new MigrationJob();
        job.setStatus(MigrationStatus.PENDING);
        job.setSourcePlatform(Platform.SPOTIFY);
        job.setTargetPlatform(Platform.YOUTUBE);
        
        assertEquals(0, job.getProcessedTracks());
        assertEquals(0, job.getFailedTracks());
        assertEquals(MigrationStatus.PENDING, job.getStatus());
    }

    @Test
    public void testMigrationTaskLinkage() {
        MigrationJob job = new MigrationJob();
        MigrationTask task = new MigrationTask();
        task.setJob(job);
        task.setStatus(MigrationStatus.COMPLETED);
        
        assertEquals(job, task.getJob());
        assertEquals(MigrationStatus.COMPLETED, task.getStatus());
    }
}
