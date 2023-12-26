package com.jinddung2.givemeticon.domain.user.facade;

import com.jinddung2.givemeticon.domain.point.domain.Point;
import com.jinddung2.givemeticon.domain.point.service.PointService;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.SignUpRequest;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SignUpFacadeTest {

    @InjectMocks
    SignUpFacade signUpFacade;
    @Mock
    UserService userService;
    @Mock
    PointService pointService;

    SignUpRequest signUpRequest;
    User user;
    Point point;
    int defaultPoint = 1000;

    @BeforeEach
    void setUp() {
        signUpRequest = new SignUpRequest("test1234@example.com", "test1234", "01012345678");
        user = User.builder()
                .id(1)
                .build();
        point = Point.builder()
                .id(1)
                .point(defaultPoint)
                .build();
    }

    @Test
    @DisplayName("회원가입 할 때 1000 포인트를 갖고 유저가 생성된다.")
    void signUp() {
        Mockito.when(pointService.createPoint()).thenReturn(point.getId());
        Mockito.when(userService.signUp(signUpRequest, point.getId())).thenReturn(user);

        int userId = signUpFacade.signUp(signUpRequest);

        Assertions.assertEquals(1, userId);
        Assertions.assertEquals(1, point.getId());
        Assertions.assertEquals(point.getId(), user.getId());
        Assertions.assertEquals(defaultPoint, point.getPoint());
    }
}