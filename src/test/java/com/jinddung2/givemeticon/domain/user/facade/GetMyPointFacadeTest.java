package com.jinddung2.givemeticon.domain.user.facade;

import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import com.jinddung2.givemeticon.domain.point.service.CashPointService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetMyPointFacadeTest {

    @InjectMocks
    GetMyPointFacade sut;

    @Mock
    UserService userService;

    @Mock
    CashPointService cashPointService;

    @Test
    @DisplayName("내 포인트를 조회하면 보유 중인 포인트를 가져온다.")
    void when_get_my_point_should_be_return_point() {
        int userId = 1;
        int cashPointId = 2;
        User user = User.builder()
                .id(userId)
                .cashPointId(cashPointId)
                .build();

        CashPoint cashPoint = CashPoint.builder()
                .id(cashPointId)
                .cashPoint(1000)
                .build();

        when(userService.getUser(userId)).thenReturn(user);
        when(cashPointService.getCashPoint(cashPointId)).thenReturn(cashPoint);

        int myPoint = sut.getMyPoint(userId);

        assertThat(myPoint).isEqualTo(cashPoint.getCashPoint());
    }
}