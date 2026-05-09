package com.example.userservice.integration;

import com.example.userservice.support.InMemoryEmailService;
import com.example.userservice.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("End-to-end auth flow")
class AuthFlowIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate rest;

    private String email;
    private static final String PASSWORD = "Test1234";

    @BeforeEach
    void setup() {
        email = "u" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        emailService.clear();
    }

    @Test
    @DisplayName("happy path: register → activate → login → verify-otp → /me/last-login")
    void happyPath() {
        ResponseEntity<Map> regRes = rest.postForEntity(url("/api/v1/auth/register"),
                json(Map.of("email", email, "password", PASSWORD)), Map.class);
        assertThat(regRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        InMemoryEmailService.SentMail activationMail = emailService.lastActivationTo(email).orElseThrow();
        String token = extractTokenFromLink(activationMail.activationLink());

        ResponseEntity<String> getActivate = rest.getForEntity(
                url("/api/v1/auth/activate?token=" + token), String.class);
        assertThat(getActivate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getActivate.getBody()).contains("Activate my account");

        ResponseEntity<Map> postActivate = rest.postForEntity(url("/api/v1/auth/activate"),
                json(Map.of("token", token)), Map.class);
        assertThat(postActivate.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> loginRes = rest.postForEntity(url("/api/v1/auth/login"),
                json(Map.of("email", email, "password", PASSWORD)), Map.class);
        assertThat(loginRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        InMemoryEmailService.SentMail otpMail = emailService.lastOtpTo(email).orElseThrow();
        assertThat(otpMail.otpCode()).matches("\\d{6}");

        ResponseEntity<Map> verifyRes = rest.postForEntity(url("/api/v1/auth/verify-otp"),
                json(Map.of(
                        "challengeId", loginRes.getBody().get("challengeId"),
                        "code", otpMail.otpCode())),
                Map.class);
        assertThat(verifyRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = (String) verifyRes.getBody().get("accessToken");
        String refreshToken = (String) verifyRes.getBody().get("refreshToken");
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();

        ResponseEntity<Map> me = rest.exchange(url("/api/v1/users/me/last-login"),
                HttpMethod.GET, withBearer(accessToken), Map.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("email")).isEqualTo(email);
        assertThat(me.getBody().get("lastLoginAt")).isNotNull();

        ResponseEntity<Map> refresh1 = rest.postForEntity(url("/api/v1/auth/refresh"),
                json(Map.of("refreshToken", refreshToken)), Map.class);
        assertThat(refresh1.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> refresh2 = rest.postForEntity(url("/api/v1/auth/refresh"),
                json(Map.of("refreshToken", refreshToken)), Map.class);
        assertThat(refresh2.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String newAccess = (String) refresh1.getBody().get("accessToken");
        ResponseEntity<Void> logout = rest.exchange(url("/api/v1/auth/logout"),
                HttpMethod.POST, withBearer(newAccess), Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> meAfterLogout = rest.exchange(url("/api/v1/users/me/last-login"),
                HttpMethod.GET, withBearer(newAccess), Map.class);
        assertThat(meAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("duplicate registration returns 409")
    void duplicateRegister() {
        rest.postForEntity(url("/api/v1/auth/register"),
                json(Map.of("email", email, "password", PASSWORD)), Map.class);
        ResponseEntity<Map> second = rest.postForEntity(url("/api/v1/auth/register"),
                json(Map.of("email", email, "password", PASSWORD)), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("activation token cannot be reused")
    void activationTokenSingleUse() {
        rest.postForEntity(url("/api/v1/auth/register"),
                json(Map.of("email", email, "password", PASSWORD)), Map.class);
        String token = extractTokenFromLink(emailService.lastActivationTo(email).orElseThrow().activationLink());

        ResponseEntity<Map> first = rest.postForEntity(url("/api/v1/auth/activate"),
                json(Map.of("token", token)), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> second = rest.postForEntity(url("/api/v1/auth/activate"),
                json(Map.of("token", token)), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("login before activation returns 403")
    void loginBeforeActivation() {
        rest.postForEntity(url("/api/v1/auth/register"),
                json(Map.of("email", email, "password", PASSWORD)), Map.class);
        ResponseEntity<Map> login = rest.postForEntity(url("/api/v1/auth/login"),
                json(Map.of("email", email, "password", PASSWORD)), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("wrong password returns 401")
    void wrongPassword() {
        registerAndActivate();
        ResponseEntity<Map> login = rest.postForEntity(url("/api/v1/auth/login"),
                json(Map.of("email", email, "password", "WrongPass99")), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("OTP wrong code 3x invalidates challenge")
    void otpAttemptsInvalidate() {
        registerAndActivate();
        ResponseEntity<Map> login = rest.postForEntity(url("/api/v1/auth/login"),
                json(Map.of("email", email, "password", PASSWORD)), Map.class);
        String challengeId = (String) login.getBody().get("challengeId");

        for (int i = 0; i < 3; i++) {
            ResponseEntity<Map> verify = rest.postForEntity(url("/api/v1/auth/verify-otp"),
                    json(Map.of("challengeId", challengeId, "code", "000000")), Map.class);
            assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        String correctCode = emailService.lastOtpTo(email).orElseThrow().otpCode();
        ResponseEntity<Map> verify = rest.postForEntity(url("/api/v1/auth/verify-otp"),
                json(Map.of("challengeId", challengeId, "code", correctCode)), Map.class);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("/me/last-login without auth returns 401")
    void meRequiresAuth() {
        ResponseEntity<Map> me = rest.getForEntity(url("/api/v1/users/me/last-login"), Map.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("resend-activation cooldown returns 429")
    void resendCooldown() {
        rest.postForEntity(url("/api/v1/auth/register"),
                json(Map.of("email", email, "password", PASSWORD)), Map.class);
        ResponseEntity<Map> first = rest.postForEntity(url("/api/v1/auth/resend-activation"),
                json(Map.of("email", email)), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> second = rest.postForEntity(url("/api/v1/auth/resend-activation"),
                json(Map.of("email", email)), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("resend for unknown email returns generic 200 (no enumeration)")
    void resendForUnknownEmail() {
        ResponseEntity<Map> res = rest.postForEntity(url("/api/v1/auth/resend-activation"),
                json(Map.of("email", "nonexistent@example.com")), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void registerAndActivate() {
        rest.postForEntity(url("/api/v1/auth/register"),
                json(Map.of("email", email, "password", PASSWORD)), Map.class);
        String token = extractTokenFromLink(emailService.lastActivationTo(email).orElseThrow().activationLink());
        rest.postForEntity(url("/api/v1/auth/activate"),
                json(Map.of("token", token)), Map.class);
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    private HttpEntity<Void> withBearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }

    private String extractTokenFromLink(String link) {
        int idx = link.indexOf("token=");
        return link.substring(idx + "token=".length());
    }
}
