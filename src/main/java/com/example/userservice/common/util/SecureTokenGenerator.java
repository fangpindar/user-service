package com.example.userservice.common.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

@Component
public class SecureTokenGenerator {

    private static final int DEFAULT_BYTE_LENGTH = 32;
    private final SecureRandom random = new SecureRandom();

    public String generateHexToken() {
        return generateHexToken(DEFAULT_BYTE_LENGTH);
    }

    public String generateHexToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String generateNumericCode(int digits) {
        StringBuilder sb = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }
}
