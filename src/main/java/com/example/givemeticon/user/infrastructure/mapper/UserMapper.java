package com.example.givemeticon.user.infrastructure.mapper;

import com.example.givemeticon.user.domain.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {

    void save(User user);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);
}
