package com.jinddung2.givemeticon.account.domain;

import lombok.Builder;

import java.time.LocalDateTime;

public class Account {
    private int id;
    private String transactionId;
    private String accountHolder;
    private String accountNumber;
    private String bankCode;
    private String bankName;
    private String birth;
    private LocalDateTime createdDate;
    private LocalDateTime deletedDate;

    @Builder
    public Account(int id,
                   String transactionId,
                   String accountHolder,
                   String accountNumber,
                   String bankCode,
                   String bankName,
                   String birth,
                   LocalDateTime createdDate,
                   LocalDateTime deletedDate
    ) {
        this.id = id;
        this.transactionId = transactionId;
        this.accountHolder = accountHolder;
        this.accountNumber = accountNumber;
        this.bankCode = bankCode;
        this.bankName = bankName;
        this.birth = birth;
        this.createdDate = createdDate;
        this.deletedDate = deletedDate;
    }
}
