package com.jinddung2.givemeticon.domain.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.TestConfig;
import com.jinddung2.givemeticon.common.config.WebConfig;
import com.jinddung2.givemeticon.common.security.interceptor.AuthInterceptor;
import com.jinddung2.givemeticon.domain.account.exception.AccountErrorCode;
import com.jinddung2.givemeticon.domain.account.exception.DuplicatedAccountNumberException;
import com.jinddung2.givemeticon.domain.account.request.CreateAccountRequest;
import com.jinddung2.givemeticon.domain.favorite.exception.AlreadyPushItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.exception.FavoriteErrorCode;
import com.jinddung2.givemeticon.domain.favorite.exception.NotPushItemFavorite;
import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.mail.service.MailSendService;
import com.jinddung2.givemeticon.domain.point.exception.CashPointErrorCode;
import com.jinddung2.givemeticon.domain.point.exception.NotFoundCashPoint;
import com.jinddung2.givemeticon.domain.user.controller.dto.UserDto;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.*;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.exception.*;
import com.jinddung2.givemeticon.domain.user.facade.*;
import com.jinddung2.givemeticon.domain.user.service.LoginService;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import com.jinddung2.givemeticon.fixture.ItemFixture;
import com.jinddung2.givemeticon.fixture.UserFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.List;

import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = UserController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                WebConfig.class,
                AuthInterceptor.class,
        }))
@Import(TestConfig.class)
public class UserControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    SignUpFacade signUpFacade;

    @MockBean
    UserService userService;

    @MockBean
    LoginService loginService;

    @MockBean
    MailSendService mailSendService;

    @MockBean
    PasswordResetFacade passwordResetFacade;

    @MockBean
    CreateAccountFacade createAccountFacade;

    @MockBean
    UserItemFavoriteFacade userItemFavoriteFacade;

    @MockBean
    GetMyPointFacade getMyPointFacade;

    MockHttpSession mockHttpSession;
    UserDto userDto;

    @BeforeEach
    void setUp() {
        mockHttpSession = new MockHttpSession();
    }

    @Test
    @DisplayName("회원 가입을 성공한다.")
    void signUp_Success() throws Exception {
        SignUpRequest request = new SignUpRequest("test1234@example.com", "test1234", "01012345678");
        User userFixture = UserFixture.createUserFixture(request.getEmail(), request.getPassword(), request.getPhone());

        when(signUpFacade.signUp(request)).thenReturn(userFixture.getId());

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(userFixture.getId()));

        verify(signUpFacade, times(1)).signUp(request);
    }

    @Test
    @DisplayName("중복된 이메일이라서 회원가입에 실패한다.")
    void signUp_Fail_Duplicate_Email() throws Exception {
        SignUpRequest request = new SignUpRequest("test1234@example.com", "test1234", "01012345678");

        doThrow(new DuplicatedEmailException()).when(signUpFacade).signUp(request);

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(UserErrorCode.DUPLICATED_EMAIL.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(UserErrorCode.DUPLICATED_EMAIL.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(UserErrorCode.DUPLICATED_EMAIL.getErrorDetail()));
    }

    @Test
    @DisplayName("중복된 휴대폰 번호라서 회원가입에 실패한다.")
    void signUp_Fail_Duplicate_Phone() throws Exception {
        SignUpRequest request = new SignUpRequest("test1234@example.com", "test1234", "01012345678");

        doThrow(new DuplicatedPhoneException()).when(signUpFacade).signUp(request);

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(UserErrorCode.DUPLICATED_PHONE.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(UserErrorCode.DUPLICATED_PHONE.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(UserErrorCode.DUPLICATED_PHONE.getErrorDetail()));
    }

    @Test
    @DisplayName("사용자 회원 정보 조회에 성공한다.")
    void get_UserInfo_Success() throws Exception {
        User userFixture = UserFixture.createUserFixture();
        UserDto result = UserDto.of(userFixture);

        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());
        when(userService.getUserInfo(userFixture.getId())).thenReturn(result);

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/users/info")
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(result.getId()))
                .andExpect(jsonPath("$.data.accountId").value(result.getAccountId()))
                .andExpect(jsonPath("$.data.cashPointId").value(result.getCashPointId()))
                .andExpect(jsonPath("$.data.email").value(result.getEmail()))
                .andExpect(jsonPath("$.data.phone").value(result.getPhone()))
                .andExpect(jsonPath("$.data.userRole").value(result.getUserRole().name()))
                .andExpect(jsonPath("$.data.isActive").value(result.isActive()))
                .andExpect(jsonPath("$.data.provider").doesNotExist()) // provider가 null일 때 존재하지 않음을 확인
                .andExpect(jsonPath("$.data.createdDate").exists())
                .andExpect(jsonPath("$.data.updatedDate").exists());

        verify(userService).getUserInfo(userFixture.getId());
    }

    @Test
    @DisplayName("해당 유저가 존재하지 않아 회원 정보 조회에 실패한다.")
    void get_UserInfo_Fail_Not_Exists() throws Exception {
        User userFixture = UserFixture.createUserFixture();
        UserDto result = UserDto.of(userFixture);
        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());

        doThrow(new NotFoundUserException()).when(userService).getUserInfo(result.getId());

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/users/info")
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(UserErrorCode.NOT_FOUND_USER.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(UserErrorCode.NOT_FOUND_USER.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(UserErrorCode.NOT_FOUND_USER.getErrorDetail()));
        ;
    }

    @Test
    @DisplayName("로그인을 성공한다.")
    void login_Success() throws Exception {
        LoginRequest request = new LoginRequest("test1234@example.com", "test1234");
        User userFixture = UserFixture.createUserFixture(request.getEmail(), request.getPassword());
        UserDto result = UserDto.of(userFixture);

        given(userService.checkLogin(request.getEmail(), request.getPassword())).willReturn(result);

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(result.getId()))
                .andExpect(jsonPath("$.data.accountId").value(result.getAccountId()))
                .andExpect(jsonPath("$.data.cashPointId").value(result.getCashPointId()))
                .andExpect(jsonPath("$.data.email").value(result.getEmail()))
                .andExpect(jsonPath("$.data.phone").value(result.getPhone()))
                .andExpect(jsonPath("$.data.userRole").value(result.getUserRole().name()))
                .andExpect(jsonPath("$.data.isActive").value(result.isActive()))
                .andExpect(jsonPath("$.data.provider").doesNotExist()) // provider가 null일 때 존재하지 않음을 확인
                .andExpect(jsonPath("$.data.createdDate").exists())
                .andExpect(jsonPath("$.data.updatedDate").exists());

        verify(loginService, times(1)).login(result.getId());
    }

    @Test
    @DisplayName("로그아웃을 성공한다.")
    void logout_Success() throws Exception {
        User userFixture = UserFixture.createUserFixture();
        String responseBody = "Successfully logout";

        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());
        willDoNothing().given(loginService).logout();

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(responseBody));

        verify(loginService, times(1)).logout();
    }

    @Test
    @DisplayName("비밀번호 변경에 성공한다.")
    void update_Password_Success() throws Exception {
        User userFixture = UserFixture.createUserFixture();
        PasswordUpdateRequest request = new PasswordUpdateRequest("test1234", "newtest1234");
        String responseBody = "Successfully update password";

        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());
        doNothing().when(userService).updatePassword(userFixture.getId(), request);

        mockMvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/users/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(responseBody));
        ;

        verify(userService, times(1)).updatePassword(userFixture.getId(), request);
    }

    @Test
    @DisplayName("해당 유저가 존재하지 않아 비밀번호 변경에 실패한다.")
    void update_Password_Fail_Not_Exists_User() throws Exception {
        User userFixture = UserFixture.createUserFixture();
        PasswordUpdateRequest request = new PasswordUpdateRequest("test1234", "newtest1234");

        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());
        doThrow(new NotFoundUserException()).when(userService).updatePassword(userFixture.getId(), request);

        mockMvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/users/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(UserErrorCode.NOT_FOUND_USER.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(UserErrorCode.NOT_FOUND_USER.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(UserErrorCode.NOT_FOUND_USER.getErrorDetail()));
    }

    @Test
    @DisplayName("이전 비밀번호가 일치하지 않아 비밀번호 변경에 실패한다.")
    void update_Password_MisMatch_Password() throws Exception {
        User userFixture = UserFixture.createUserFixture();
        PasswordUpdateRequest request = new PasswordUpdateRequest("test1234", "newtest1234");

        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());
        doThrow(new MisMatchPasswordException()).when(userService).updatePassword(userFixture.getId(), request);

        mockMvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/users/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(UserErrorCode.INCORRECT_PASSWORD.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(UserErrorCode.INCORRECT_PASSWORD.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(UserErrorCode.INCORRECT_PASSWORD.getErrorDetail()));
    }

    @Test
    @DisplayName("등록된 이메일에 임시 비밀번호 발급에 성공한다.")
    void send_Temporary_Password() throws Exception {
        PasswordResetRequest request = new PasswordResetRequest("test1234@example.com");
        User userFixture = UserFixture.createUserFixture();
        String responseBody = "Successfully reset password";

        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());
        doNothing().when(passwordResetFacade).resetPasswordAndSendEmail(request.email());

        mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/users/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(responseBody));

        verify(passwordResetFacade, times(1)).resetPasswordAndSendEmail(request.email());
    }

    @Test
    @DisplayName("유저가 계좌 생성하는데 성공한다.")
    void create_Account_Link_User_Account_Id() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest("testHolder", "0000", "testBank", "000101");
        User userFixture = UserFixture.createUserFixture();

        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());
        when(createAccountFacade.createAccount(userFixture.getId(), request)).thenReturn(userFixture.getAccountId());

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(userFixture.getAccountId()));

        verify(createAccountFacade, times(1)).createAccount(userFixture.getId(), request);
    }

    @Test
    @DisplayName("계좌번호가 이미 등록되어서 유저와 계좌 연결에 실패한다.")
    void create_Account_Fail_Duplicated_Account_Number() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest("testHolder", "0000", "testBank", "000101");
        User userFixture = UserFixture.createUserFixture();

        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());
        doThrow(new DuplicatedAccountNumberException())
                .when(createAccountFacade).createAccount(userFixture.getId(), request);

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(AccountErrorCode.DUPLICATED_ACCOUNT_NUMBER.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(AccountErrorCode.DUPLICATED_ACCOUNT_NUMBER.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(AccountErrorCode.DUPLICATED_ACCOUNT_NUMBER.getErrorDetail()));
        ;
    }

    @Test
    @DisplayName("특정 상품에 좋아요를 누르는데 성공한다.")
    void push_Favorite_Item() throws Exception {
        User userFixture = UserFixture.createUserFixture();
        Item itemFixture = ItemFixture.createItemFixture();
        String responseBody = "Successfully like item " + itemFixture.getId();

        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());
        doNothing().when(userItemFavoriteFacade).pushItemFavorite(userFixture.getId(), itemFixture.getId());

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/items/" + itemFixture.getId() + "/favorite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(responseBody));

        verify(userItemFavoriteFacade, times(1))
                .pushItemFavorite(userFixture.getId(), itemFixture.getId());
    }

    @Test
    @DisplayName("상품에 좋아요를 눌렀지만 이미 좋아요 한 상품이라 실패한다.")
    void push_Favorite_Item_Fail_Already_Item_Favorite() throws Exception {
        User userFixture = UserFixture.createUserFixture();
        Item itemFixture = ItemFixture.createItemFixture();

        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());
        doThrow(new AlreadyPushItemFavorite())
                .when(userItemFavoriteFacade).pushItemFavorite(userFixture.getId(), itemFixture.getId());

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/items/" + itemFixture.getId() + "/favorite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(FavoriteErrorCode.ALREADY_PUSH_ITEM_FAVORITE.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(FavoriteErrorCode.ALREADY_PUSH_ITEM_FAVORITE.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(FavoriteErrorCode.ALREADY_PUSH_ITEM_FAVORITE.getErrorDetail()));
    }

    @Test
    @DisplayName("좋아요를 누른 상품을 취소하는 것을 확인한다.")
    void cancel_Favorite_Item() throws Exception {
        User userFixture = UserFixture.createUserFixture();
        Item itemFixture = ItemFixture.createItemFixture();
        String responseBody = "Successfully cancel like item: " + itemFixture.getId();

        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());
        doNothing().when(userItemFavoriteFacade).cancelItemFavorite(userFixture.getId(), itemFixture.getId());

        mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/users/items/" + itemFixture.getId() + "/cancel-favorite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(responseBody));
        ;

        verify(userItemFavoriteFacade, times(1)).cancelItemFavorite(userFixture.getId(), itemFixture.getId());
    }

    @Test
    @DisplayName("좋아요 한 적이 없는 상품이라 좋아요 취소에 실패한다.")
    void cancel_Favorite_Item_Fail_Not_Push_Item_Favorite() throws Exception {
        User userFixture = UserFixture.createUserFixture();
        Item itemFixture = ItemFixture.createItemFixture();

        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());
        doThrow(new NotPushItemFavorite())
                .when(userItemFavoriteFacade).cancelItemFavorite(userFixture.getId(), itemFixture.getId());

        mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/users/items/" + itemFixture.getId() + "/cancel-favorite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(FavoriteErrorCode.NOT_PUSH_ITEM_FAVORITE.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(FavoriteErrorCode.NOT_PUSH_ITEM_FAVORITE.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(FavoriteErrorCode.NOT_PUSH_ITEM_FAVORITE.getErrorDetail()));
    }

    @Test
    @DisplayName("내가 좋아요한 상품들을 조회하는데 성공한다.")
    void get_My_Favorite_Items() throws Exception {
        User userFixture = UserFixture.createUserFixture();
        Item itemFixture1 = ItemFixture.createItemFixture(30);
        Item itemFixture2 = ItemFixture.createItemFixture(10);
        Item itemFixture3 = ItemFixture.createItemFixture(20);
        List<ItemFavoriteDto> responseBody = new ArrayList<>();
        responseBody.add(ItemFavoriteDto.of(itemFixture1));
        responseBody.add(ItemFavoriteDto.of(itemFixture2));
        responseBody.add(ItemFavoriteDto.of(itemFixture3));

        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());
        when(userItemFavoriteFacade.getMyFavoriteItems(userFixture.getId())).thenReturn(responseBody);

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/users/items/my-favorite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(responseBody.size()))
                .andExpect(jsonPath("$.data[0].name").exists())
                .andExpect(jsonPath("$.data[0].price").exists())
                .andExpect(jsonPath("$.data[1].name").exists())
                .andExpect(jsonPath("$.data[1].price").exists())
                .andExpect(jsonPath("$.data[2].name").exists())
                .andExpect(jsonPath("$.data[2].price").exists());

        verify(userItemFavoriteFacade, times(1)).getMyFavoriteItems(userFixture.getId());
    }

    @Test
    @DisplayName("내 포인트 조회 api가 성공한다.")
    void get_my_point() throws Exception {
        User userFixture = UserFixture.createUserFixture();
        int myPoint = 10000;

        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());
        when(getMyPointFacade.getMyPoint(userFixture.getId())).thenReturn(myPoint);

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/users/my-point")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(myPoint));

        verify(getMyPointFacade, times(1)).getMyPoint(userFixture.getId());
    }

    @Test
    @DisplayName("캐시 포인트 데이터가 존재하지 않아 포인트 조회 api가 실패한다.")
    void get_my_point_fail_not_found_cash_point() throws Exception {
        User userFixture = UserFixture.createUserFixture();

        mockHttpSession.setAttribute(LOGIN_USER, userFixture.getId());
        doThrow(new NotFoundCashPoint())
                .when(getMyPointFacade).getMyPoint(userFixture.getId());

        mockMvc.perform(MockMvcRequestBuilders
                .get("/api/v1/users/my-point")
                .contentType(MediaType.APPLICATION_JSON)
                .session(mockHttpSession))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(CashPointErrorCode.NOT_FOUND_CASH_POINT.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(CashPointErrorCode.NOT_FOUND_CASH_POINT.getHttpStatus().name()))
                .andExpect(jsonPath("$.errorDetail").value(CashPointErrorCode.NOT_FOUND_CASH_POINT.getErrorDetail()));;

    }
}