package com.jinddung2.givemeticon.common.security.provider;

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

    @BeforeEach
    void setUp() {
        String secretKey = "my1test2secret3keymy1test2secret3keymy1test2secret3keymy1test2secret3key";
        jwtTokenProvider = new JwtTokenProvider(secretKey);
        subject = "testSubject";
    }

    @Test
    @DisplayName("토큰 생성에 성공한다.")
    void generate_Token() {
        Date expiredTime = new Date(System.currentTimeMillis() + (60 * 60 * 1000));

        String token = jwtTokenProvider.generate(subject, expiredTime);

        assertTrue(token.length() > 0);

        String[] tokenParts = token.split("\\.");
        assertEquals(3, tokenParts.length);
    }
}