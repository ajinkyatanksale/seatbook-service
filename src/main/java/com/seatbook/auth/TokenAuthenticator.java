package com.seatbook.auth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class TokenAuthenticator {

    private static final int MAX_TOKEN_LENGTH = 128;
    private static final String USER_PREFIX = "user:";

    private final String adminToken;

    public TokenAuthenticator(@Value("${app.admin-token}") String adminToken) {
        this.adminToken = adminToken;
    }

    public Optional<AuthenticationUser> authenticate(String rawToken) {

        if (rawToken == null) {
            return Optional.empty();
        }
        String token = rawToken.trim();
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }

        if (token.equals(adminToken)) {
            return Optional.of(new AuthenticationUser("admin", true));
        }

        String userId = token.startsWith(USER_PREFIX)
                ? token.substring(USER_PREFIX.length()).trim()
                : token;
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticationUser(userId, false));
    }
}
