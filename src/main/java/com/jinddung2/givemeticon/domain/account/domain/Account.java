package com.jinddung2.givemeticon.domain.account.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class Account {
    private int id;
    private String accountHolder;
    private String accountNumber;
    private String bankName;
    private String birth;
    private LocalDateTime createdDate;
    private LocalDateTime deletedDate;

    @Builder
    public Account(int id,
                   String accountHolder,
                   String accountNumber,
                   String bankName,
                   String birth,
                   LocalDateTime createdDate,
                   LocalDateTime deletedDate
    ) {
        this.id = id;
        this.accountHolder = accountHolder;
        this.accountNumber = accountNumber;
        this.bankName = bankName;
        this.birth = birth;
        this.createdDate = createdDate;
        this.deletedDate = deletedDate;
    }
}
