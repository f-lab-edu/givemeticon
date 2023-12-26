package com.jinddung2.givemeticon.domain.user.service;

import com.jinddung2.givemeticon.domain.user.controller.dto.UserDto;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.PasswordUpdateRequest;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.SignUpRequest;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.domain.UserRole;
import com.jinddung2.givemeticon.domain.user.exception.*;
import com.jinddung2.givemeticon.domain.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User signUp(SignUpRequest request, int pointId) {
        checkUserValidity(request);
        String encryptedPassword = passwordEncoder.encode(request.getPassword());

        User user = request.toEntity(encryptedPassword);
        user.updateUserRole(UserRole.USER);
        user.connectPoint(pointId);
        userMapper.save(user);

        return user;
    }

    public User getUser(int userId) {
        return userMapper.findById(userId)
                .orElseThrow(NotFoundUserException::new);
    }

    public User getUser(String email) {
        return userMapper.findByEmail(email)
                .orElseThrow(NotFoundEmailException::new);
    }

    public UserDto getUserInfo(int userId) {
        User user = getUser(userId);
        return UserDto.of(user);
    }

    public boolean isExists(int userId) {
        return userMapper.existsById(userId);
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
        User user = getUser(userId);

        if (!user.isPasswordMatch(passwordEncoder, request.oldPassword())) {
            throw new MisMatchPasswordException();
        }

        String encryptedPassword = passwordEncoder.encode(request.newPassword());
        user.updatePassword(encryptedPassword);
        userMapper.updatePassword(userId, encryptedPassword);
    }

    public void updateAccount(int userId, int accountId) {
        User user = getUser(userId);
        user.createAccount(accountId);
        log.info("user={}", user);
        userMapper.updateAccount(userId, accountId);
    }

    public void resetPassword(String email, String tempPassword) {
        User user = getUser(email);
        String encryptedPassword = passwordEncoder.encode(tempPassword);
        user.updatePassword(encryptedPassword);
        userMapper.updatePassword(user.getId(), encryptedPassword);
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
