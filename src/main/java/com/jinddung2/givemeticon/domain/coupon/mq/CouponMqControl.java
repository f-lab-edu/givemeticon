package com.jinddung2.givemeticon.domain.coupon.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.domain.coupon.domain.CouponEvent;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CouponMqControl {
    private long eventId;
    private String configVersion;
    private String openToken;
    private LocalDateTime verifiedAt;
    private boolean ingestPaused;

    public static String version(CouponEvent event) {
        if (event.getSettingsLockedAt() == null) throw new IllegalStateException("MQ settings are not locked");
        var node = new ObjectMapper().createObjectNode();
        node.put("eventId", event.getId());
        node.put("publicId", event.getPublicId());
        node.put("startsAtUtc", java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(event.getStartsAtUtc()) + "Z");
        node.put("totalQuantity", event.getTotalQuantity());
        node.put("highQuantity", event.getHighQuantity());
        node.put("highPoints", event.getHighPoints());
        node.put("normalPoints", event.getNormalPoints());
        node.put("settingsLockedAt", java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(event.getSettingsLockedAt()) + "Z");
        try {
            return CouponMqProtocol.sha256(CouponMqProtocol.canonicalConfig(new ObjectMapper().writeValueAsBytes(node)));
        } catch (java.io.IOException error) { throw new IllegalStateException("cannot encode MQ configuration"); }
    }
}
