package com.jinddung2.givemeticon.user.presentation;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.user.application.LoginService;
import com.jinddung2.givemeticon.user.application.UserService;
import com.jinddung2.givemeticon.user.application.dto.UserDto;
import com.jinddung2.givemeticon.user.presentation.request.LoginRequest;
import com.jinddung2.givemeticon.user.presentation.request.SignUpRequest;
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

    @PostMapping("/sign-up")
    public ResponseEntity<ApiResponse<Void>> signUp(@RequestBody @Validated SignUpRequest request) {
        userService.signUp(request);
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.CREATED);
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
}
