package com.spotifyyoutube.migrator.identity.application;

import com.spotifyyoutube.migrator.identity.domain.User;

public interface AuthenticationService {
    User register(String email, String password, String displayName);
    User login(String email, String password);
}
