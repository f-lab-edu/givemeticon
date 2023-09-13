package com.jinddung2.givemeticon.user.infrastructure.mapper;

import com.jinddung2.givemeticon.user.domain.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {

    void save(User user);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);
}
