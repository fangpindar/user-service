package com.example.userservice.auth.service.impl;

import com.example.userservice.auth.jwt.UserPrincipal;
import com.example.userservice.auth.service.LogoutService;
import com.example.userservice.auth.service.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class LogoutServiceImpl implements LogoutService {

    private static final Logger log = LoggerFactory.getLogger(LogoutServiceImpl.class);

    private final TokenService tokenService;

    public LogoutServiceImpl(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public void logout(UserPrincipal principal, Instant accessTokenExp) {
        tokenService.revokeAccess(principal.accessJti(), accessTokenExp);
        tokenService.revokeAllRefreshForUser(principal.userId());
        log.info("User {} logged out", principal.userId());
    }
}
