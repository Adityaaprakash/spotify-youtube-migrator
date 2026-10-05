package com.spotifyyoutube.migrator.playlist.repository;

import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.playlist.domain.Playlist;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
public class PlaylistRepositoryTest {

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    public void testFindByUserIdAndExternalIdAndPlatform() {
        User user = new User("test2@example.com");
        user = userRepository.save(user);

        Playlist playlist = new Playlist();
        playlist.setUser(user);
        playlist.setName("My Favorites");
        playlist.setPlatform(Platform.SPOTIFY);
        playlist.setExternalId("ext-id-001");
        playlistRepository.save(playlist);

        Optional<Playlist> found = playlistRepository.findByUserIdAndExternalIdAndPlatform(user.getId(), "ext-id-001", Platform.SPOTIFY);
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("My Favorites");
    }
}
