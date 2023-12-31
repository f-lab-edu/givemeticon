package com.jinddung2.givemeticon.domain.user.controller;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.domain.account.request.CreateAccountRequest;
import com.jinddung2.givemeticon.domain.user.controller.dto.UserDto;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.*;
import com.jinddung2.givemeticon.domain.user.facade.CreateAccountFacade;
import com.jinddung2.givemeticon.domain.user.facade.PasswordResetFacade;
import com.jinddung2.givemeticon.domain.user.facade.SignUpFacade;
import com.jinddung2.givemeticon.domain.user.facade.UserItemFavoriteFacade;
import com.jinddung2.givemeticon.domain.user.service.LoginService;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final SignUpFacade signUpFacade;
    private final LoginService loginService;
    private final UserItemFavoriteFacade userItemFavoriteFacade;
    private final PasswordResetFacade passwordResetFacade;
    private final CreateAccountFacade createAccountFacade;

    @PostMapping("/sign-up")
    public ResponseEntity<ApiResponse<Integer>> signUp(@RequestBody @Validated SignUpRequest request) {
        Integer userId = signUpFacade.signUp(request);
        return new ResponseEntity<>(ApiResponse.success(userId), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Void>> login(@RequestBody @Validated LoginRequest request) {
        UserDto userDto = userService.checkLogin(request.getEmail(), request.getPassword());
        loginService.login(userDto.getId());
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.OK);
    }

    @GetMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout() {
        loginService.logout();
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.OK);
    }

    @GetMapping("/info")
    public ResponseEntity<ApiResponse<UserDto>> getUser(@SessionAttribute(name = LOGIN_USER) int userId) {
        UserDto userDto = userService.getUserInfo(userId);
        return new ResponseEntity<>(ApiResponse.success(userDto), HttpStatus.OK);
    }

    @PatchMapping("/password")
    public ResponseEntity<ApiResponse<Void>> updatePassword(@SessionAttribute(name = LOGIN_USER) int userId,
                                                            @RequestBody PasswordUpdateRequest request) {
        userService.updatePassword(userId, request);
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.OK);
    }

    @PutMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@RequestBody PasswordResetRequest request) throws MessagingException {
        passwordResetFacade.resetPasswordAndSendEmail(request.email());
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.OK);
    }

    @PostMapping("/account")
    public ResponseEntity<ApiResponse<Integer>> createAccount(@SessionAttribute(name = LOGIN_USER) int userId,
                                                              @RequestBody @Validated CreateAccountRequest request) {
        int accountId = createAccountFacade.createAccount(userId, request);
        return new ResponseEntity<>(ApiResponse.success(accountId), HttpStatus.OK);
    }

    @PostMapping("/items/{itemId}/favorite")
    public ResponseEntity<ApiResponse<Void>> pushFavoriteItem(@SessionAttribute(name = LOGIN_USER) int userId,
                                                              @PathVariable(name = "itemId") int itemId) {
        userItemFavoriteFacade.pushItemFavorite(userId, itemId);
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.OK);
    }

    @DeleteMapping("/items/{itemId}/cancel-favorite")
    public ResponseEntity<ApiResponse<Void>> cancelFavoriteItem(@SessionAttribute(name = LOGIN_USER) int userId,
                                                                @PathVariable(name = "itemId") int itemId) {
        userItemFavoriteFacade.cancelItemFavorite(userId, itemId);
        return new ResponseEntity<>(ApiResponse.success(), HttpStatus.OK);
    }

    @GetMapping("/items/my-favorite")
    public ResponseEntity<ApiResponse<List<ItemFavoriteDto>>> getMyFavoriteItems(@SessionAttribute(name = LOGIN_USER) int userId) {
        List<ItemFavoriteDto> myFavoriteItems = userItemFavoriteFacade.getMyFavoriteItems(userId);
        return new ResponseEntity<>(ApiResponse.success(myFavoriteItems), HttpStatus.OK);
    }
}
