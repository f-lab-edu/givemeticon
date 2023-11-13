package com.jinddung2.givemeticon.domain.user.mapper;

import com.jinddung2.givemeticon.domain.user.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface UserMapper {

    int save(User user);

    Optional<User> findById(int id);

    Optional<User> findByEmail(String email);

    boolean existsById(int id);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    void updatePassword(@Param("id") int id,
                        @Param("newPassword") String newPassword);

    void updateAccount(@Param("userId") int userId,
                       @Param("accountId") int accountId);
}
