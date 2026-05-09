package com.example.userservice.auth;

import com.example.userservice.auth.jwt.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class LogoutService {

    private static final Logger log = LoggerFactory.getLogger(LogoutService.class);

    private final TokenService tokenService;

    public LogoutService(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Logout the current user:
     *   1. Blacklist the access JWT (until its original expiry)
     *   2. Delete all refresh tokens for the user
     */
    public void logout(UserPrincipal principal, Instant accessTokenExp) {
        tokenService.revokeAccess(principal.accessJti(), accessTokenExp);
        tokenService.revokeAllRefreshForUser(principal.userId());
        log.info("User {} logged out", principal.userId());
    }
}
