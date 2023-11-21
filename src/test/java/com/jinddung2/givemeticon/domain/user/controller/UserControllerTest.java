package com.jinddung2.givemeticon.domain.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.common.security.provider.JwtTokenProvider;
import com.jinddung2.givemeticon.domain.account.exception.DuplicatedAccountNumberException;
import com.jinddung2.givemeticon.domain.account.request.CreateAccountRequest;
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
    void signUp_Success() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signUpRequest)))
                .andExpect(status().isCreated());

        // Verify
        Mockito.verify(userService).signUp(signUpRequest);
    }

    @Test
    void signUp_Fail_Duplicate_Email() throws Exception {
        doThrow(new DuplicatedEmailException()).when(userService).signUp(signUpRequest);
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
    void signUp_Fail_Duplicate_Phone() throws Exception {
        doThrow(new DuplicatedPhoneException()).when(userService).signUp(signUpRequest);
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
    void create_Account_Link_User_Account_Id_Fail() throws Exception {
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
    @DisplayName("특정 상품에 좋아요를 누른다.")
    void push_Favorite_Item() throws Exception {
        int itemId = 1;
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/items/" + itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .session(mockHttpSession)
                        .content(objectMapper.writeValueAsString(createAccountRequest)))
                .andExpect(status().isOk());

        Mockito.verify(userItemFavoriteFacade).pushFavorite(userDto.getId(), itemId);
    }

    @Test
    @DisplayName("내가 좋아요한 상품들을 조회한다.")
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