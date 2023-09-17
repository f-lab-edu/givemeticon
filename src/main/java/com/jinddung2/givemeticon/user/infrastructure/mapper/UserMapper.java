package com.jinddung2.givemeticon.user.infrastructure.mapper;

import com.jinddung2.givemeticon.user.domain.User;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface UserMapper {

    void save(User user);

    Optional<User> findById(String email);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);
}
