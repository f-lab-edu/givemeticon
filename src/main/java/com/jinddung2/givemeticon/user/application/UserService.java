package com.jinddung2.givemeticon.user.application;

import com.jinddung2.givemeticon.user.domain.User;
import com.jinddung2.givemeticon.user.domain.UserRole;
import com.jinddung2.givemeticon.user.exception.DuplicatedEmailException;
import com.jinddung2.givemeticon.user.exception.DuplicatedPhoneException;
import com.jinddung2.givemeticon.user.infrastructure.mapper.UserMapper;
import com.jinddung2.givemeticon.user.presentation.request.SignUpRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public void signUp(SignUpRequest request) {
        checkUserValidity(request);
        String encryptedPassword = passwordEncoder.encode(request.getPassword());

        User user = request.toEntity(encryptedPassword);
        user.updateUserRole(UserRole.USER);
        userMapper.save(user);
    }

    private void checkUserValidity(SignUpRequest request) {
        if (userMapper.existsByEmail(request.getEmail())) {
            throw new DuplicatedEmailException();
        }

        if (userMapper.existsByPhone(request.getPhone())) {
            throw new DuplicatedPhoneException();
        }
    }
}
