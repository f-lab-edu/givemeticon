package com.jinddung2.givemeticon.domain.notification.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class Notification {

    private int id;
    private int saleId;
    private int sellerId;
    private String message;
    private boolean isRead;
    private LocalDateTime createdDate;

    public Notification(int saleId, int sellerId, String message, boolean isRead) {
        this.saleId = saleId;
        this.sellerId = sellerId;
        this.message = message;
        this.isRead = isRead;
    }
}
