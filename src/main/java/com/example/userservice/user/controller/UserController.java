package com.example.userservice.user.controller;

import com.example.userservice.auth.jwt.UserPrincipal;
import com.example.userservice.user.dto.LastLoginResponse;
import com.example.userservice.user.service.UserQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "2. User", description = "User self-service queries (always uses caller's identity)")
@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    private final UserQueryService userQueryService;

    public UserController(UserQueryService userQueryService) {
        this.userQueryService = userQueryService;
    }

    @Operation(
            summary = "Get my last login time",
            description = "Returns the authenticated user's most recent successful login timestamp. " +
                    "User identity is taken from the JWT — there is no way to query another user's last login.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/last-login")
    public ResponseEntity<LastLoginResponse> getMyLastLogin(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(userQueryService.getLastLogin(principal.userId()));
    }
}
