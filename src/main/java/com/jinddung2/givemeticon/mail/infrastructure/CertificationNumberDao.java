package com.jinddung2.givemeticon.mail.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

import static com.jinddung2.givemeticon.mail.constans.EmailConstants.EMAIL_VERIFICATION_LIMIT_IN_SECONDS;

@Repository
@RequiredArgsConstructor
public class CertificationNumberDao {

    private final StringRedisTemplate redisTemplate;

    public void saveCertificationNumber(String email, String certificationNumber) {
        redisTemplate.opsForValue()
                .set(email, certificationNumber,
                        Duration.ofSeconds(EMAIL_VERIFICATION_LIMIT_IN_SECONDS));
    }

    public boolean hasKey(String email) {
        Boolean keyExists = redisTemplate.hasKey(email);
        return keyExists != null && keyExists;
    }
}
