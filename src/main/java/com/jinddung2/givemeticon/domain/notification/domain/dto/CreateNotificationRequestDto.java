package com.jinddung2.givemeticon.domain.notification.domain.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record CreateNotificationRequestDto(
        String eventId,
        int saleId,
        int sellerId,
        String message
) {
    public CreateNotificationRequestDto {
        if (eventId == null || eventId.isBlank()) {
            eventId = deriveEventId(saleId, sellerId, message);
        }
    }

    public CreateNotificationRequestDto(int saleId, int sellerId, String message) {
        this(null, saleId, sellerId, message);
    }

    private static String deriveEventId(int saleId, int sellerId, String message) {
        String payload = saleId + "|" + sellerId + "|" + message;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }
}
