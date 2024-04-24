package com.jinddung2.givemeticon.domain.notification.domain.dto;

public record CreateNotificationRequestDto (
        int saleId,
        int sellerId,
        String message
) {
}
