package com.jinddung2.givemeticon.account.infrastructure;

import com.jinddung2.givemeticon.account.exception.MisMatchBank;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

@Getter
@AllArgsConstructor
public enum BankCode {
    KDB("002", "KDB산업은행"),
    SC("023", "SC제일은행"),
    JEONBUK("037", "전북은행"),
    IBK("003", "IBK기업은행"),
    KOREA_CITY("027", "한국씨티은행"),
    GYEONGNAM("039", "경남은행"),
    KB("004", "KB국민은행"),
    DAEGU("031", "대구은행"),
    KEB("081", "하나은행"),
    SH("007", "수협은행"),
    BUSAN("032", "부산은행"),
    SHINHAN("088", "신한은행"),
    NH("011", "NH농협은행"),
    GWANGJIU("034", "광주은행"),
    K("089", "케이뱅크"),
    WOORI("020", "우리은행"),
    JEJU("035", "제주은행"),
    KAKAO("090", "카카오뱅크");

    private final String code;
    private final String name;

    public static BankCode of(String codeStr) {
        if (codeStr == null) {
            throw new IllegalArgumentException();
        }

        for (BankCode bc : BankCode.values()) {
            if (Objects.equals(bc.code, codeStr)) {
                return bc;
            }
        }

        throw new MisMatchBank();
    }
}