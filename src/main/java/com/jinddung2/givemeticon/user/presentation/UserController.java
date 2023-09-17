package com.jinddung2.givemeticon.user.presentation;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.user.application.LoginService;
import com.jinddung2.givemeticon.user.application.UserService;
import com.jinddung2.givemeticon.user.application.dto.UserDto;
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
    public ResponseEntity<Void> signUp(@RequestBody @Validated SignUpRequest request) {
        userService.signUp(request);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @GetMapping("/info")
    public ResponseEntity<ApiResponse<UserDto>> getUser(@RequestParam String email) {
        UserDto userDto = userService.getUser(email);
        return new ResponseEntity<>(ApiResponse.success(userDto), HttpStatus.OK);
    }
}
