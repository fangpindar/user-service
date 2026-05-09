package com.example.userservice.auth.jwt;

import com.example.userservice.config.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    public enum TokenType { ACCESS, REFRESH }

    private final JwtProperties props;
    private final SecretKey accessKey;
    private final SecretKey refreshKey;

    public JwtTokenProvider(JwtProperties props) {
        this.props = props;
        this.accessKey = Keys.hmacShaKeyFor(props.accessSecret().getBytes(StandardCharsets.UTF_8));
        this.refreshKey = Keys.hmacShaKeyFor(props.refreshSecret().getBytes(StandardCharsets.UTF_8));
    }

    public IssuedToken issueAccess(Long userId, String email) {
        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofMinutes(props.accessTtlMinutes()));
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .issuer(props.issuer())
                .subject(String.valueOf(userId))
                .id(jti)
                .claim("email", email)
                .claim("type", TokenType.ACCESS.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(accessKey)
                .compact();
        return new IssuedToken(token, jti, exp);
    }

    public IssuedToken issueRefresh(Long userId) {
        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofDays(props.refreshTtlDays()));
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .issuer(props.issuer())
                .subject(String.valueOf(userId))
                .id(jti)
                .claim("type", TokenType.REFRESH.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(refreshKey)
                .compact();
        return new IssuedToken(token, jti, exp);
    }

    public ParsedToken parseAccess(String token) {
        return parse(token, accessKey, TokenType.ACCESS);
    }

    public ParsedToken parseRefresh(String token) {
        return parse(token, refreshKey, TokenType.REFRESH);
    }

    private ParsedToken parse(String token, SecretKey key, TokenType expectedType) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String typeStr = claims.get("type", String.class);
            if (typeStr == null || !typeStr.equals(expectedType.name())) {
                throw new JwtException("Token type mismatch: expected " + expectedType + ", got " + typeStr);
            }
            Long userId = Long.parseLong(claims.getSubject());
            String email = claims.get("email", String.class);
            return new ParsedToken(userId, email, claims.getId(), claims.getExpiration().toInstant());
        } catch (NumberFormatException e) {
            throw new JwtException("Invalid subject in token", e);
        }
    }

    public record IssuedToken(String token, String jti, Instant expiresAt) {}

    public record ParsedToken(Long userId, String email, String jti, Instant expiresAt) {}
}
