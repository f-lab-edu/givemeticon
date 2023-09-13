package com.jinddung2.givemeticon.user.presentation;

import com.jinddung2.givemeticon.user.application.UserService;
import com.jinddung2.givemeticon.user.presentation.request.SignUpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/sign-up")
    public ResponseEntity<Void> signUp(@RequestBody @Validated SignUpRequest request) {
        userService.signUp(request);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }
}
