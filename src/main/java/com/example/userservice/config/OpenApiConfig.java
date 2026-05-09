package com.example.userservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("User Service API")
                        .version("v1")
                        .description("""
                                Member registration, 2FA login, and last-login query service.

                                Authentication flow:
                                1. POST /register → activation email sent
                                2. Click email link → confirmation page → POST /activate
                                3. POST /login → OTP email sent → returns challengeId
                                4. POST /verify-otp with challengeId + code → returns access + refresh tokens
                                5. Use access token (Bearer) for /me/* endpoints
                                6. POST /refresh to rotate tokens
                                7. POST /logout to revoke
                                """))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SCHEME_NAME, new SecurityScheme()
                                .name(SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste your access token (without the 'Bearer ' prefix).")));
    }
}
