package com.jinddung2.givemeticon.common.security.provider;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class JwtTokenProviderTest {

    @Mock
    JwtTokenProvider jwtTokenProvider;

    String subject;
    String token;

    @BeforeEach
    void setUp() {
        String secretKey = "my1test2secret3keymy1test2secret3keymy1test2secret3keymy1test2secret3key";
        jwtTokenProvider = new JwtTokenProvider(secretKey);
        subject = "testSubject";
        Date expiredTime = new Date(System.currentTimeMillis() + (60 * 60 * 1000));

        token = jwtTokenProvider.generate(subject, expiredTime);
    }

    @Test
    void generate_Token() {
        assertTrue(token.length() > 0);

        String[] tokenParts = token.split("\\.");
        assertEquals(3, tokenParts.length);
    }

    @Test
    @DisplayName("토큰 검증에 성공한다.")
    void validate_Token_Fail() {
        Assertions.assertTrue(jwtTokenProvider.validateToken(token));
    }

    @Test
    @DisplayName("토큰 검증에 실패한다.")
    void validate_Token() {
        String wrongToken = "InvalidToken";
        Assertions.assertFalse(jwtTokenProvider.validateToken(wrongToken));
    }

    @Test
    @DisplayName("토큰 추출에 성공한다.")
    void extract_Token() {
        String result = jwtTokenProvider.extractSubject(token);

        Assertions.assertEquals(result, subject);
    }
}