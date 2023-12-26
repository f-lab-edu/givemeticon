package com.jinddung2.givemeticon.domain.user.facade;

import com.jinddung2.givemeticon.domain.point.service.PointService;
import com.jinddung2.givemeticon.domain.user.controller.dto.request.SignUpRequest;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SignUpFacade {

    private final UserService userService;
    private final PointService pointService;

    @Transactional
    public int signUp(SignUpRequest request) {
        int pointId = pointService.createPoint();
        User user = userService.signUp(request, pointId);
        return user.getId();
    }
}
