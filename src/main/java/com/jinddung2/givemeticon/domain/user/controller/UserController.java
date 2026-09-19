package com.jinddung2.givemeticon.domain.user.controller;

import com.jinddung2.givemeticon.domain.account.request.CreateAccountRequest;
import com.jinddung2.givemeticon.domain.user.controller.dto.UserDto;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.*;
import com.jinddung2.givemeticon.domain.user.controller.dto.response.LoginResponse;
import com.jinddung2.givemeticon.domain.user.facade.*;
import com.jinddung2.givemeticon.domain.user.service.LoginService;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
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
    private final GetMyPointFacade getMyPointFacade;

    @PostMapping("/sign-up")
    public int signUp(@RequestBody @Validated SignUpRequest request) {
        return signUpFacade.signUp(request);
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody @Validated LoginRequest request) {
        UserDto userDto = userService.checkLogin(request.getEmail(), request.getPassword());
        int sessionId = loginService.login(userDto.getId());
        return LoginResponse.of(sessionId, userDto);
    }

    @PostMapping("/logout")
    public String logout() {
        loginService.logout();
        return "Successfully logout";
    }

    @PostMapping("/account")
    public int createAccount(@SessionAttribute(name = LOGIN_USER) int userId,
                                                              @RequestBody @Validated CreateAccountRequest request) {
        return createAccountFacade.createAccount(userId, request);
    }

    @PostMapping("/items/{itemId}/favorite")
    public String pushFavoriteItem(@SessionAttribute(name = LOGIN_USER) int userId,
                                                              @PathVariable(name = "itemId") int itemId) {
        userItemFavoriteFacade.pushItemFavorite(userId, itemId);
        return "Successfully like item " + itemId;
    }

    @GetMapping("/info")
    public UserDto getUser(@SessionAttribute(name = LOGIN_USER) int userId) {
        return userService.getUserInfo(userId);
    }

    @GetMapping("/items/my-favorite")
    public List<ItemFavoriteDto> getMyFavoriteItems(@SessionAttribute(name = LOGIN_USER) int userId) {
        return userItemFavoriteFacade.getMyFavoriteItems(userId);
    }

    @GetMapping("/my-point")
    public int getMyPoint(@SessionAttribute(name = LOGIN_USER) int userId) {
        return getMyPointFacade.getMyPoint(userId);
    }

    @PatchMapping("/password")
    public String updatePassword(@SessionAttribute(name = LOGIN_USER) int userId,
                                                            @RequestBody PasswordUpdateRequest request) {
        userService.updatePassword(userId, request);
        return "Successfully update password";
    }

    @PutMapping("/reset-password")
    public String resetPassword(@RequestBody PasswordResetRequest request) throws MessagingException {
        passwordResetFacade.resetPasswordAndSendEmail(request.email());
        return "Successfully reset password";
    }

    @DeleteMapping("/items/{itemId}/cancel-favorite")
    public String cancelFavoriteItem(@SessionAttribute(name = LOGIN_USER) int userId,
                                                                @PathVariable(name = "itemId") int itemId) {
        userItemFavoriteFacade.cancelItemFavorite(userId, itemId);
        return "Successfully cancel like item: " + itemId;
    }
}
