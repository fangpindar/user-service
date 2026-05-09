package com.example.userservice.auth;

import com.example.userservice.auth.dto.LoginChallengeResponse;
import com.example.userservice.auth.dto.LoginRequest;
import com.example.userservice.auth.dto.MessageResponse;
import com.example.userservice.auth.dto.RefreshRequest;
import com.example.userservice.auth.dto.RegisterRequest;
import com.example.userservice.auth.dto.ResendActivationRequest;
import com.example.userservice.auth.dto.TokenPairResponse;
import com.example.userservice.auth.dto.VerifyOtpRequest;
import com.example.userservice.auth.jwt.JwtTokenProvider;
import com.example.userservice.auth.jwt.JwtTokenProvider.ParsedToken;
import com.example.userservice.auth.jwt.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "1. Authentication", description = "Registration, activation, login, 2FA, refresh, logout")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistrationService registrationService;
    private final ResendActivationService resendActivationService;
    private final LoginService loginService;
    private final TokenService tokenService;
    private final LogoutService logoutService;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(RegistrationService registrationService,
                          ResendActivationService resendActivationService,
                          LoginService loginService,
                          TokenService tokenService,
                          LogoutService logoutService,
                          JwtTokenProvider jwtTokenProvider) {
        this.registrationService = registrationService;
        this.resendActivationService = resendActivationService;
        this.loginService = loginService;
        this.tokenService = tokenService;
        this.logoutService = logoutService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Operation(summary = "Register a new account",
            description = "Creates a PENDING_ACTIVATION user and sends an activation email. " +
                    "Returns 409 if email is already registered.")
    @PostMapping("/register")
    public ResponseEntity<MessageResponse> register(@Valid @RequestBody RegisterRequest request) {
        registrationService.register(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MessageResponse("Registration successful. Please check your email to activate your account."));
    }

    @Operation(summary = "Resend activation email",
            description = "Always returns 200 with a generic message regardless of email validity " +
                    "(prevents account enumeration). Rate limited: 60s cooldown, 5 requests per email per day.")
    @PostMapping("/resend-activation")
    public ResponseEntity<MessageResponse> resendActivation(@Valid @RequestBody ResendActivationRequest request) {
        resendActivationService.resend(request.email());
        return ResponseEntity.ok(new MessageResponse(
                "If your email is registered and pending activation, an activation email has been sent."));
    }

    @Operation(summary = "Login (Phase 1: credentials)",
            description = "Validates email + password, sends a 6-digit OTP via email. " +
                    "Returns a challengeId to use in /verify-otp. " +
                    "Account is locked for 15 min after 5 failed attempts.")
    @PostMapping("/login")
    public ResponseEntity<LoginChallengeResponse> login(@Valid @RequestBody LoginRequest request) {
        OtpService.Issued issued = loginService.startLogin(request.email(), request.password());
        return ResponseEntity.ok(new LoginChallengeResponse(
                issued.challengeId(),
                issued.expiresInSeconds(),
                "OTP code has been sent to your email. Use it with the challengeId in /verify-otp."));
    }

    @Operation(summary = "Verify OTP (Phase 2)",
            description = "Validates the 6-digit OTP and returns access + refresh tokens. " +
                    "OTP challenge invalidates after 3 failed attempts.")
    @PostMapping("/verify-otp")
    public ResponseEntity<TokenPairResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request,
                                                       HttpServletRequest httpRequest) {
        TokenService.TokenPair pair = loginService.completeLogin(
                request.challengeId(),
                request.code(),
                resolveIp(httpRequest),
                httpRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(TokenPairResponse.of(
                pair.accessToken(), pair.refreshToken(), pair.expiresInSeconds()));
    }

    @Operation(summary = "Refresh access token (with rotation)",
            description = "Exchanges a refresh token for a NEW token pair. " +
                    "The old refresh token is immediately invalidated. " +
                    "Replaying an old refresh token returns 401.")
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshRequest request) {
        ParsedToken parsed;
        try {
            parsed = jwtTokenProvider.parseRefresh(request.refreshToken());
        } catch (Exception ex) {
            return unauthorized("Invalid refresh token.");
        }

        TokenService.TokenPair pair = tokenService.rotateRefresh(request.refreshToken(), parsed.email());
        if (pair == null) {
            return unauthorized("Refresh token is invalid, expired, or has been replayed.");
        }
        return ResponseEntity.ok(TokenPairResponse.of(
                pair.accessToken(), pair.refreshToken(), pair.expiresInSeconds()));
    }

    @Operation(summary = "Logout",
            description = "Blacklists the current access token and invalidates all refresh tokens for the user.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal UserPrincipal principal,
                                       HttpServletRequest request) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length()).trim() : null;
        if (token == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        ParsedToken parsed = jwtTokenProvider.parseAccess(token);
        logoutService.logout(principal, parsed.expiresAt());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<MessageResponse> unauthorized(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse(message));
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }
}
