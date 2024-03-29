package com.jinddung2.givemeticon.domain.user.facade;

import com.jinddung2.givemeticon.domain.point.domain.CashPoint;
import com.jinddung2.givemeticon.domain.point.service.CashPointService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetMyPointFacade {

    private final UserService userService;
    private final CashPointService cashPointService;

    public int getMyPoint(int userId) {
        User user = userService.getUser(userId);
        CashPoint point = cashPointService.getCashPoint(user.getCashPointId());

        return point.getCashPoint();
    }
}
