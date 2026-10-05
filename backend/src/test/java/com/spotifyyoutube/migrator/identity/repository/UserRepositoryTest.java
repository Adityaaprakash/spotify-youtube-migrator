package com.spotifyyoutube.migrator.identity.repository;

import com.spotifyyoutube.migrator.identity.domain.User;
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
public class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    public void testSaveAndFindByEmail() {
        User user = new User("test@example.com");
        user.setSpotifyId("spotify-123");
        user.setYoutubeId("youtube-456");
        
        userRepository.save(user);

        Optional<User> found = userRepository.findByEmail("test@example.com");
        assertThat(found).isPresent();
        assertThat(found.get().getSpotifyId()).isEqualTo("spotify-123");
    }
}
