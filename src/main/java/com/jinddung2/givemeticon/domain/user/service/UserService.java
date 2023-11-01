package com.jinddung2.givemeticon.domain.user.service;

import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.domain.UserRole;
import com.jinddung2.givemeticon.domain.user.dto.UserDto;
import com.jinddung2.givemeticon.domain.user.dto.request.PasswordUpdateRequest;
import com.jinddung2.givemeticon.domain.user.dto.request.SignUpRequest;
import com.jinddung2.givemeticon.domain.user.exception.*;
import com.jinddung2.givemeticon.domain.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public int signUp(SignUpRequest request) {
        checkUserValidity(request);
        String encryptedPassword = passwordEncoder.encode(request.getPassword());

        User user = request.toEntity(encryptedPassword);
        user.updateUserRole(UserRole.USER);
        userMapper.save(user);

        return user.getId();
    }

    public UserDto getUserInfo(int userId) {
        User user = validateUser(userId);
        return UserDto.of(user);
    }

    public UserDto checkLogin(String email, String password) {
        User user = userMapper.findByEmail(email)
                .orElseThrow(NotFoundEmailException::new);
        boolean isMatch = passwordEncoder.matches(password, user.getPassword());

        if (!isMatch) {
            throw new MisMatchPasswordException();
        }
        return UserDto.of(user);
    }

    public void updatePassword(int userId, PasswordUpdateRequest request) {
        User user = validateUser(userId);

        if (!user.isPasswordMatch(passwordEncoder, request.oldPassword())) {
            throw new MisMatchPasswordException();
        }

        String encryptedPassword = passwordEncoder.encode(request.newPassword());
        user.updatePassword(encryptedPassword);
        userMapper.updatePassword(userId, encryptedPassword);
    }

    public void updateAccount(int userId, int accountId) {
        User user = validateUser(userId);
        user.createAccount(accountId);
        log.info("user={}", user);
        userMapper.updateAccount(userId, accountId);
    }

    public void resetPassword(String email, String tempPassword) {
        User user = validateUser(email);
        String encryptedPassword = passwordEncoder.encode(tempPassword);
        user.updatePassword(encryptedPassword);
        userMapper.updatePassword(user.getId(), encryptedPassword);
    }

    public User validateUser(int userId) {
        return userMapper.findById(userId)
                .orElseThrow(NotFoundUserException::new);
    }

    private void checkUserValidity(SignUpRequest request) {
        if (userMapper.existsByEmail(request.getEmail())) {
            throw new DuplicatedEmailException();
        }

        if (userMapper.existsByPhone(request.getPhone())) {
            throw new DuplicatedPhoneException();
        }
    }

    private User validateUser(String email) {
        return userMapper.findByEmail(email)
                .orElseThrow(NotFoundEmailException::new);
    }
}
