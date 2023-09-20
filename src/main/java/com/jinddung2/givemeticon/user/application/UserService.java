package com.jinddung2.givemeticon.user.application;

import com.jinddung2.givemeticon.user.application.dto.UserDto;
import com.jinddung2.givemeticon.user.domain.User;
import com.jinddung2.givemeticon.user.domain.UserRole;
import com.jinddung2.givemeticon.user.exception.DuplicatedEmailException;
import com.jinddung2.givemeticon.user.exception.DuplicatedPhoneException;
import com.jinddung2.givemeticon.user.exception.MisMatchPasswordException;
import com.jinddung2.givemeticon.user.exception.NotFoundEmailException;
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

    public UserDto getUser(String email) {
        User user = userMapper.findById(email)
                .orElseThrow(NotFoundEmailException::new);
        return toDto(user);
    }

    public UserDto checkLogin(String email, String password) {
        UserDto user = getUser(email);
        boolean isMatch = passwordEncoder.matches(password, user.getPassword());

        if (!isMatch) {
            throw new MisMatchPasswordException();
        }
        return user;
    }

    private void checkUserValidity(SignUpRequest request) {
        if (userMapper.existsByEmail(request.getEmail())) {
            throw new DuplicatedEmailException();
        }

        if (userMapper.existsByPhone(request.getPhone())) {
            throw new DuplicatedPhoneException();
        }
    }

    private UserDto toDto(User user) {
        return UserDto.builder()
                .accountId(user.getAccountId())
                .email(user.getEmail())
                .phone(user.getPhone())
                .password(user.getPassword())
                .userRole(user.getUserRole())
                .createdDate(user.getCreatedDate())
                .updatedDate(user.getUpdatedDate())
                .deletedDate(user.getDeletedDate())
                .isActive(user.isActive())
                .build();
    }
}
