package com.jinddung2.givemeticon.domain.account.dto.request;

import com.jinddung2.givemeticon.domain.account.domain.Account;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank(message = "예금주 입력은 필수입니다.")
        String accountHolder,
        @NotBlank(message = "계좌번호 입력은 필수입니다.")
        String accountNumber,
        @NotBlank(message = "은행 입력은 필수입니다.")
        String bankName,
        @NotBlank(message = "생년월일 입력은은 필수입니다.")
        @Size(min = 6, max = 6, message = "yyyyMMdd 형식으로 6자리만 입력해주세요.")
        String birth
) {
    public Account toEntity() {
        return Account.builder()
                .accountHolder(accountHolder)
                .accountNumber(accountNumber)
                .bankName(bankName)
                .birth(birth)
                .build();
    }
}
