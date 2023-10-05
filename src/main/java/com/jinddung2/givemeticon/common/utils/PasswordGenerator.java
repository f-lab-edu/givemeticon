package com.jinddung2.givemeticon.common.utils;

import org.springframework.stereotype.Component;

@Component
public class PasswordGenerator {
    public String createTemporaryPassword() {
        char[] charSet = new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
                'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z'};

        StringBuilder tempPassword = new StringBuilder();
        int idx;

        for (int i = 0; i < 12; i++) {
            idx = (int) (charSet.length * Math.random());
            tempPassword.append(charSet[idx]);
        }

        return tempPassword.toString();
    }
}
