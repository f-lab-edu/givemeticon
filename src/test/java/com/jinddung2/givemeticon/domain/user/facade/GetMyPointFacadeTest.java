package com.jinddung2.givemeticon.domain.user.facade;

import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import com.jinddung2.givemeticon.domain.point.service.CashPointService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import com.jinddung2.givemeticon.fixture.CashPointFixture;
import com.jinddung2.givemeticon.fixture.UserFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

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

    LocalDateTime now = LocalDateTime.now();

    @Test
    @DisplayName("내 포인트를 조회하면 보유 중인 포인트를 가져온다.")
    void when_get_my_point_should_be_return_point() {
        User userFixture = UserFixture.createUserFixture(now);
        CashPoint cashPointFixture = CashPointFixture.createCashPointFixture();

        when(userService.getUser(userFixture.getId())).thenReturn(userFixture);
        when(cashPointService.getCashPoint(cashPointFixture.getId())).thenReturn(cashPointFixture);

        int myPoint = sut.getMyPoint(userFixture.getId());

        assertThat(myPoint).isEqualTo(cashPointFixture.getCashPoint());
    }
}