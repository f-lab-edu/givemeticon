package com.jinddung2.givemeticon.user.application;

import com.jinddung2.givemeticon.user.application.dto.UserDto;
import com.jinddung2.givemeticon.user.domain.User;
import com.jinddung2.givemeticon.user.domain.UserRole;
import com.jinddung2.givemeticon.user.exception.*;
import com.jinddung2.givemeticon.user.infrastructure.mapper.UserMapper;
import com.jinddung2.givemeticon.user.presentation.request.PasswordUpdateRequest;
import com.jinddung2.givemeticon.user.presentation.request.SignUpRequest;
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

    public Integer signUp(SignUpRequest request) {
        checkUserValidity(request);
        String encryptedPassword = passwordEncoder.encode(request.getPassword());

        User user = request.toEntity(encryptedPassword);
        user.updateUserRole(UserRole.USER);
        userMapper.save(user);

        return user.getId();
    }

    public UserDto getUserInfo(int userId) {
        User user = getUser(userId);
        return toDto(user);
    }

    public UserDto checkLogin(String email, String password) {
        User user = userMapper.findByEmail(email)
                .orElseThrow(NotFoundEmailException::new);
        boolean isMatch = passwordEncoder.matches(password, user.getPassword());

        if (!isMatch) {
            throw new MisMatchPasswordException();
        }
        return toDto(user);
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

    private User getUser(int userId) {
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

    private User getUser(String email) {
        return userMapper.findByEmail(email)
                .orElseThrow(NotFoundEmailException::new);
    }

    private UserDto toDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .accountId(user.getAccountId())
                .email(user.getEmail())
                .phone(user.getPhone())
                .userRole(user.getUserRole())
                .provider(user.getProvider())
                .createdDate(user.getCreatedDate())
                .updatedDate(user.getUpdatedDate())
                .deletedDate(user.getDeletedDate())
                .isActive(user.isActive())
                .build();
    }
}
