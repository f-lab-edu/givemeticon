package com.jinddung2.givemeticon.account.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;

@Slf4j
public class StringToBankCodeConverter implements Converter<String, BankCode> {
    @Override
    public BankCode convert(String source) {
        try {
            return BankCode.of(source);
        } catch (IllegalArgumentException e) {
            log.info("bankcode converter fail", e);
            return null;
        }
    }
}
