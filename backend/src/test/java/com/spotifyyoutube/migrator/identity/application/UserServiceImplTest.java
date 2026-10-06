package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    public void testGetUserById_Success() {
        UUID id = UUID.randomUUID();
        User user = new User("test@test.com");
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        User result = userService.getUserById(id);
        assertEquals("test@test.com", result.getEmail());
    }

    @Test
    public void testGetUserById_NotFound() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.getUserById(id));
    }

    @Test
    public void testGetUserByEmail_Success() {
        User user = new User("test@test.com");
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));

        User result = userService.getUserByEmail("test@test.com");
        assertEquals("test@test.com", result.getEmail());
    }

    @Test
    public void testGetUserByEmail_NotFound() {
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.getUserByEmail("test@test.com"));
    }
}
