package com.jinddung2.givemeticon.domain.user.facade;

import com.jinddung2.givemeticon.common.annotation.DistributedLock;
import com.jinddung2.givemeticon.domain.point.service.CashPointService;
import com.jinddung2.givemeticon.domain.user.domain.User;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class SpendCashPointFacade {

    private final UserService userService;
    private final CashPointService cashPointService;

    /**
     * 지갑(userId) 단위로 락을 걸어 동시 사용 요청을 직렬화한 뒤, 실제 차감은
     * CashPointService#spendPoint 하나의 트랜잭션으로 원자 처리한다 - 쿠폰 발급 흐름
     * (CreateCouponFacade -> CouponService#issueCoupon)과 같은 패턴이다: 락은 별도
     * 빈(bean)의 메서드에서, 원자적 DB 반영은 또 다른 @Transactional 메서드에서 - 같은
     * 메서드에 @DistributedLock과 @Transactional을 함께 붙이지 않는다(두 AOP의 적용
     * 순서가 보장되지 않아 트랜잭션이 락보다 먼저 시작될 수 있다).
     */
    @DistributedLock(key = "#userId")
    public void spendPoint(int userId, int amount) {
        User user = userService.getUser(userId);
        cashPointService.spendPoint(user, amount, LocalDate.now());
    }
}
