package com.jinddung2.givemeticon.domain.user.controller.dto.request;

import jakarta.validation.constraints.Positive;

public record SpendCashPointRequest(
        @Positive(message = "사용할 포인트는 0보다 커야 합니다.")
        int amount
) {
}
