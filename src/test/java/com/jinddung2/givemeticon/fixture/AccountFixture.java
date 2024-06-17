package com.jinddung2.givemeticon.fixture;

import com.jinddung2.givemeticon.domain.account.domain.Account;

import java.time.LocalDateTime;

public class AccountFixture {

    public static Account createAccountFixture() {
        LocalDateTime now = LocalDateTime.now();
        return Account.builder()
                .id(2)
                .accountHolder("testHolder")
                .accountNumber("0000")
                .bankName("testBank")
                .birth("000101")
                .createdDate(now)
                .deletedDate(null)
                .build();
    }

    public static Account createAccountFixture(String accountHolder, String accountNumber, String bankName, String birth) {
        LocalDateTime now = LocalDateTime.now();
        return Account.builder()
                .id(2)
                .accountHolder(accountHolder)
                .accountNumber(accountNumber)
                .bankName(bankName)
                .birth(birth)
                .createdDate(now)
                .deletedDate(null)
                .build();
    }
}
