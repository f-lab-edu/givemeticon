package com.jinddung2.givemeticon.account.infrastructure.mapper;

import com.jinddung2.givemeticon.account.domain.Account;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface AccountMapper {

    int save(Account account);

    Optional<Account> findByTransactionId(String transactionId);
}
