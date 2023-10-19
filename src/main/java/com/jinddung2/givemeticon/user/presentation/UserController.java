package com.jinddung2.givemeticon.user.presentation;

import com.jinddung2.givemeticon.account.dto.request.CreateAccountRequest;
import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.user.application.LoginService;
import com.jinddung2.givemeticon.user.application.PasswordResetAdapter;
import com.jinddung2.givemeticon.user.application.UserService;
import com.jinddung2.givemeticon.user.application.dto.UserDto;
import com.jinddung2.givemeticon.user.presentation.facade.CreateAccountFacade;
import com.jinddung2.givemeticon.user.presentation.request.LoginRequest;
import com.jinddung2.givemeticon.user.presentation.request.PasswordResetRequest;
import com.jinddung2.givemeticon.user.presentation.request.PasswordUpdateRequest;
import com.jinddung2.givemeticon.user.presentation.request.SignUpRequest;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final LoginService loginService;
    private final PasswordResetAdapter passwordResetAdapter;
    private final CreateAccountFacade createAccountFacade;

    @PostMapping("/sign-up")
    public ResponseEntity<ApiResponse<Integer>> signUp(@RequestBody @Validated SignUpRequest request) {
        Integer userId = userService.signUp(request);
        return new ResponseEntity<>(ApiResponse.success(userId), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Void>> login(@RequestBody @Validated LoginRequest request) {
        UserDto userDto = userService.checkLogin(request.getEmail(), request.getPassword());
        loginService.login(userDto.getEmail());
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.OK);
    }

    @GetMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout() {
        loginService.logout();
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.OK);
    }

    @GetMapping("/{userId}/info")
    public ResponseEntity<ApiResponse<UserDto>> getUser(@PathVariable(name = "userId") int userId) {
        UserDto userDto = userService.getUserInfo(userId);
        return new ResponseEntity<>(ApiResponse.success(userDto), HttpStatus.OK);
    }

    @PatchMapping("/{userId}/password")
    public ResponseEntity<ApiResponse<Void>> updatePassword(@PathVariable(name = "userId") int userId,
                                                            @RequestBody PasswordUpdateRequest request) {
        userService.updatePassword(userId, request);
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.OK);
    }

    @PutMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@RequestBody PasswordResetRequest request) throws MessagingException {
        passwordResetAdapter.resetPasswordAndSendEmail(request.email());
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.OK);
    }

    @PostMapping("{userId}/account")
    public ResponseEntity<ApiResponse<Integer>> createAccount(@PathVariable(name = "userId") int userId,
                                                              @RequestBody @Validated CreateAccountRequest request) {
        int accountId = createAccountFacade.createAccount(userId, request);
        return new ResponseEntity<>(ApiResponse.success(accountId), HttpStatus.OK);
    }
}
