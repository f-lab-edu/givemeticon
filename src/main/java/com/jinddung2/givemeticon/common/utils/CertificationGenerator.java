package com.jinddung2.givemeticon.common.utils;

import org.springframework.stereotype.Component;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Random;

@Component
public class CertificationGenerator {
    public String createCertificationNumber() throws NoSuchAlgorithmException {
        String result;

        do {
            int num = SecureRandom.getInstanceStrong().nextInt(999999);
            result = String.valueOf(num);
        } while (result.length() != 6);

        return result;
    }

    public String createRandomNumber(int bound) {
        Random random = new Random();

        // 16자리 숫자 생성
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bound; i++) {
            int digit = random.nextInt(10);
            sb.append(digit);
        }
        return sb.toString();
    }
}
