package com.jinddung2.givemeticon.account.mapper;

import com.jinddung2.givemeticon.account.domain.Account;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AccountMapper {

    int save(Account account);

    boolean existsByAccountNumber(String accountNumber);
}
