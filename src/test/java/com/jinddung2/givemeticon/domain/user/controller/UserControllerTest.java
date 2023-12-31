package com.jinddung2.givemeticon.domain.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.common.security.provider.JwtTokenProvider;
import com.jinddung2.givemeticon.domain.account.exception.DuplicatedAccountNumberException;
import com.jinddung2.givemeticon.domain.account.request.CreateAccountRequest;
import com.jinddung2.givemeticon.domain.favorite.exception.AlreadyPushItemFavorite;
import com.jinddung2.givemeticon.domain.favorite.exception.NotPushItemFavorite;
import com.jinddung2.givemeticon.domain.mail.service.MailSendService;
import com.jinddung2.givemeticon.domain.user.controller.dto.UserDto;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.LoginRequest;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.PasswordResetRequest;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.PasswordUpdateRequest;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.SignUpRequest;
import com.jinddung2.givemeticon.domain.user.exception.DuplicatedEmailException;
import com.jinddung2.givemeticon.domain.user.exception.DuplicatedPhoneException;
import com.jinddung2.givemeticon.domain.user.exception.MisMatchPasswordException;
import com.jinddung2.givemeticon.domain.user.exception.NotFoundUserException;
import com.jinddung2.givemeticon.domain.user.facade.CreateAccountFacade;
import com.jinddung2.givemeticon.domain.user.facade.PasswordResetFacade;
import com.jinddung2.givemeticon.domain.user.facade.SignUpFacade;
import com.jinddung2.givemeticon.domain.user.facade.UserItemFavoriteFacade;
import com.jinddung2.givemeticon.domain.user.service.LoginService;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.jinddung2.givemeticon.common.exception.ErrorCode.ALREADY_PUSH_ITEMFAVORITE;
import static com.jinddung2.givemeticon.common.exception.ErrorCode.NOT_PUSH_ITEMFAVORITE;
import static com.jinddung2.givemeticon.domain.user.constants.SessionConstants.LOGIN_USER;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = UserController.class)
public class UserControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper; // JSON 객체로 변환하기 위한 ObjectMapper
    @MockBean
    SignUpFacade signUpFacade;
    @MockBean
    UserService userService; // MockBean으로 UserService 주입
    @MockBean
    LoginService loginService;
    @MockBean
    JwtTokenProvider jwtTokenProvider;
    @MockBean
    MailSendService mailSendService;
    @MockBean
    PasswordResetFacade passwordResetFacade;
    @MockBean
    CreateAccountFacade createAccountFacade;
    @MockBean
    UserItemFavoriteFacade userItemFavoriteFacade;

    MockHttpSession mockHttpSession;
    SignUpRequest signUpRequest;
    LoginRequest loginRequest;
    UserDto userDto;
    PasswordUpdateRequest passwordUpdateRequest;
    PasswordResetRequest passwordResetRequest;
    CreateAccountRequest createAccountRequest;

    @BeforeEach
    void setUp() {
        signUpRequest = new SignUpRequest("test1234@example.com", "test1234", "01012345678");
        loginRequest = new LoginRequest("test1234@example.com", "test1234");
        int fakeUserId = 100;
        userDto = UserDto.builder()
                .id(fakeUserId)
                .email("test1234@example.com")
                .password("test1234")
                .build();
        mockHttpSession = new MockHttpSession();
        mockHttpSession.setAttribute(LOGIN_USER, fakeUserId);
        passwordUpdateRequest = new PasswordUpdateRequest("test1234", "newtest1234");
        passwordResetRequest = new PasswordResetRequest("test1234@example.com");
        createAccountRequest = new CreateAccountRequest("testHolder", "0000", "testBank", "000101");
    }

    @Test
    @DisplayName("회원 가입을 성공한다.")
    void signUp_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andExpect(status().isCreated());

        // Verify
        Mockito.verify(signUpFacade).signUp(signUpRequest);
    }

    @Test
    @DisplayName("중복된 이메일이라서 회원가입에 실패한다.")
    void signUp_Fail_Duplicate_Email() throws Exception {
        doThrow(new DuplicatedEmailException()).when(signUpFacade).signUp(signUpRequest);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                .post("/api/v1/users/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signUpRequest)));

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("이미 존재하는 이메일입니다."));
    }

    @Test
    @DisplayName("중복된 휴대폰 번호라서 회원가입에 실패한다.")
    void signUp_Fail_Duplicate_Phone() throws Exception {
        doThrow(new DuplicatedPhoneException()).when(signUpFacade).signUp(signUpRequest);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("이미 존재하는 휴대폰 번호입니다."));
    }

    @Test
    @DisplayName("사용자 회원 정보 조회에 성공한다.")
    void get_UserInfo_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/users/info")
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andDo(print())
                .andExpect(status().isOk());

        // Verify
        Mockito.verify(userService).getUserInfo(userDto.getId());
    }

    @Test
    @DisplayName("해당 유저가 존재하지 않아 회원 정보 조회에 실패한다.")
    void get_UserInfo_Fail_Not_Exists() throws Exception {
        doThrow(new NotFoundUserException()).when(userService).getUserInfo(userDto.getId());

        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/users/info")
                        .session(mockHttpSession)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        // Verify
        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("존재하지 않는 회원입니다."));
    }

    @Test
    @DisplayName("로그인을 성공한다.")
    void login_Success() throws Exception {
        given(userService.checkLogin(loginRequest.getEmail(), loginRequest.getPassword())).willReturn(userDto);
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andExpect(status().isOk());

        // Verify
        Mockito.verify(loginService).login(userDto.getId());
    }

    @Test
    @DisplayName("로그아웃을 성공한다.")
    void logout_Success() throws Exception {
        willDoNothing().given(loginService).logout();
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/users/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andExpect(status().isOk());

        // Verify
        Mockito.verify(loginService).logout();
    }

    @Test
    @DisplayName("비밀번호 변경에 성공한다.")
    void update_Password_Success() throws Exception {
        doNothing().when(userService).updatePassword(userDto.getId(), passwordUpdateRequest);
        mockMvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/users/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(passwordUpdateRequest)))
                .andExpect(status().isOk());

        // Verify
        Mockito.verify(userService).updatePassword(userDto.getId(), passwordUpdateRequest);
    }

    @Test
    @DisplayName("해당 유저가 존재하지 않아 비밀번호 변경에 실패한다.")
    void update_Password_Fail_Not_Exists_User() throws Exception {
        doThrow(new NotFoundUserException()).when(userService).updatePassword(userDto.getId(), passwordUpdateRequest);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/users/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(passwordUpdateRequest)))
                .andExpect(status().isBadRequest());

        // Verify
        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("존재하지 않는 회원입니다."));
    }

    @Test
    @DisplayName("이전 비밀번호가 일치하지 않아 비밀번호 변경에 실패한다.")
    void update_Password_MisMatch_Password() throws Exception {
        doThrow(new MisMatchPasswordException()).when(userService).updatePassword(userDto.getId(), passwordUpdateRequest);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/users/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(passwordUpdateRequest)))
                .andExpect(status().isBadRequest());

        // Verify
        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("패스워드가 일치하지 않습니다."));

    }

    @Test
    @DisplayName("등록된 이메일에 임시 비밀번호 발급에 성공한다.")
    void send_Temporary_Password() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/users/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(passwordResetRequest)))
                .andExpect(status().isOk());

        // Verify
        Mockito.verify(passwordResetFacade).resetPasswordAndSendEmail(passwordResetRequest.email());
    }

    @Test
    @DisplayName("유저가 계좌 생성하는데 성공한다.")
    void create_Account_Link_User_Account_Id() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(createAccountRequest)))
                .andExpect(status().isOk());

        // Verify
        Mockito.verify(createAccountFacade).createAccount(userDto.getId(), createAccountRequest);
    }

    @Test
    @DisplayName("계좌번호가 이미 등록되어서 유저와 계좌 연결에 실패한다.")
    void create_Account_Fail_Duplicated_Account_Number() throws Exception {
        doThrow(new DuplicatedAccountNumberException())
                .when(createAccountFacade).createAccount(userDto.getId(), createAccountRequest);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(createAccountRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value("이미 등록된 계좌번호 입니다."));
    }

    @Test
    @DisplayName("특정 상품에 좋아요를 누르는데 성공한다.")
    void push_Favorite_Item() throws Exception {
        int itemId = 1;
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/items/" + itemId + "/favorite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(createAccountRequest)))
                .andExpect(status().isOk());

        Mockito.verify(userItemFavoriteFacade).pushItemFavorite(userDto.getId(), itemId);
    }

    @Test
    @DisplayName("상품에 좋아요를 눌렀지만 이미 좋아요 한 상품이라 실패한다.")
    void push_Favorite_Item_Fail_Already_Item_Favorite() throws Exception {
        int itemId = 1;
        Mockito.doThrow(new AlreadyPushItemFavorite())
                .when(userItemFavoriteFacade).pushItemFavorite(userDto.getId(), itemId);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/items/" + itemId + "/favorite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(createAccountRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value(ALREADY_PUSH_ITEMFAVORITE.getMessage()));
    }

    @Test
    @DisplayName("좋아요를 누른 상품을 취소하는 것을 확인한다.")
    void cancel_Favorite_Item() throws Exception {
        int itemId = 1;
        mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/users/items/" + itemId + "/cancel-favorite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(createAccountRequest)))
                .andExpect(status().isOk());

        Mockito.verify(userItemFavoriteFacade).cancelItemFavorite(userDto.getId(), itemId);
    }

    @Test
    @DisplayName("좋아요 한 적이 없는 상품이라 좋아요 취소에 실패한다.")
    void cancel_Favorite_Item_Fail_Not_Push_Item_Favorite() throws Exception {
        int itemId = 1;
        Mockito.doThrow(new NotPushItemFavorite())
                .when(userItemFavoriteFacade).cancelItemFavorite(userDto.getId(), itemId);
        ResultActions resultActions = mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/users/items/" + itemId + "/cancel-favorite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(createAccountRequest)))
                .andExpect(status().isBadRequest());

        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("FAIL"))
                .andExpect(jsonPath("$.data.message").value(NOT_PUSH_ITEMFAVORITE.getMessage()));
    }

    @Test
    @DisplayName("내가 좋아요한 상품들을 조회하는데 성공한다.")
    void get_My_Favorite_Items() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/users/items/my-favorite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(createAccountRequest)))
                .andExpect(status().isOk());

        Mockito.verify(userItemFavoriteFacade).getMyFavoriteItems(userDto.getId());
    }
}