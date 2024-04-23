package com.jinddung2.givemeticon.common.utils;

import org.springframework.stereotype.Component;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

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

    public String createCouponNumber (int length){
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder coupon = new StringBuilder();

        for (int i = 1; i <= length; i++) {
            int idx = random.nextInt(characters.length());
            coupon.append(characters.charAt(idx));
        }

        return coupon.toString();
    }
}
