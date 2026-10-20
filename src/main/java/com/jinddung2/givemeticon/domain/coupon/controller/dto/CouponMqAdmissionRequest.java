package com.jinddung2.givemeticon.domain.coupon.controller.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record CouponMqAdmissionRequest(String attemptId) {
    public static String normalize(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "attemptId must be a UUID");
        return value.toLowerCase(Locale.ROOT);
    }
    public static CouponMqAdmissionRequest parse(byte[] body) {
        try {
            if (body == null || body.length == 0 || body.length > 4096) throw new IllegalArgumentException();
            var mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var node = mapper.readTree(body);
            if (!node.isObject() || node.size() != 1 || !node.path("attemptId").isTextual()) throw new IllegalArgumentException();
            return new CouponMqAdmissionRequest(normalize(node.get("attemptId").textValue()));
        } catch (Exception invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only client attemptId is accepted"); }
    }
}
