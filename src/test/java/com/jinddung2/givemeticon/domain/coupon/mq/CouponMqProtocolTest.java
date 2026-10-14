package com.jinddung2.givemeticon.domain.coupon.mq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class CouponMqProtocolTest {
    static byte[] fixture(String name) throws Exception {
        try (var in = CouponMqProtocolTest.class.getResourceAsStream("/coupon-mq/" + name)) {
            return in.readAllBytes();
        }
    }
    static String payload() throws Exception { return new String(fixture("payload.json"), StandardCharsets.UTF_8); }
    @ParameterizedTest
    @ValueSource(strings={"config", "escaping-config", "payload", "null-token"})
    void authoritativeBytesAndHashes(String name) throws Exception {
        byte[] expected=fixture(name+".json");
        byte[] actual=name.contains("config") ? CouponMqProtocol.canonicalConfig(expected) : CouponMqProtocol.canonicalPayload(expected);
        assertArrayEquals(expected,actual);
        assertEquals(new String(fixture(name+".sha256"),StandardCharsets.UTF_8).trim(),CouponMqProtocol.sha256(actual));
    }
    @Test void reorderedInputAndNormalizedUuid() throws Exception {
        String raw=payload().replace("\"schemaVersion\":1,", "").replace("}", ",\"schemaVersion\":1}");
        assertArrayEquals(fixture("payload.json"),CouponMqProtocol.canonicalPayload(raw.getBytes(StandardCharsets.UTF_8)));
        String normalized=payload().replace("000000000001","00000000000A");
        assertTrue(new String(CouponMqProtocol.canonicalPayload(normalized.getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8).contains("00000000000a"));
    }
    @ParameterizedTest @ValueSource(strings={"1.0","1e0","\"1\"","2147483648","-2147483649","null"})
    void rejectsMemberWireTypesAndRange(String value) throws Exception {
        assertThrows(IllegalArgumentException.class,()->CouponMqProtocol.canonicalPayload(payload().replace("\"memberId\":91","\"memberId\":"+value).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void longPrecisionAndBounds() throws Exception {
        for(String value:new String[]{"9223372036854775807","-9223372036854775808","9007199254740993"}) {
            String s=payload().replace("\"eventId\":7","\"eventId\":"+value);
            assertTrue(new String(CouponMqProtocol.canonicalPayload(s.getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8).contains(value));
        }
        for(String value:new String[]{"9223372036854775808","-9223372036854775809","7.0","7e0","\"7\""}) {
            assertThrows(IllegalArgumentException.class,()->CouponMqProtocol.canonicalPayload(payload().replace("\"eventId\":7","\"eventId\":"+value).getBytes(StandardCharsets.UTF_8)));
        }
    }
    @Test void duplicateMissingExtraSchemaAndSyntax() throws Exception {
        String s=payload();
        for(String invalid:new String[]{s.replace("{","{\"eventId\":7,"),s.replace("\"openToken\":","\"extra\":"),s.replace("\"schemaVersion\":1","\"schemaVersion\":2"),s+"{}",s.replace("\"eventId\":7","\"eventId\":+7"),s.replace("\"eventId\":7","\"eventId\":07"),"[]"})
            assertThrows(IllegalArgumentException.class,()->CouponMqProtocol.canonicalPayload(invalid.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void tokenAndHashValidationAndNormalPublishNullGuard() throws Exception {
        for(String value:new String[]{"\"\"","\"wrong\"","12"})
            assertThrows(IllegalArgumentException.class,()->CouponMqProtocol.canonicalPayload(payload().replace("\"00000000-0000-4000-8000-000000000002\"",value).getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->CouponMqProtocol.canonicalPayload(payload().replace("5662a3dc","zzzzzzzz").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->CouponMqProtocol.publishBody(fixture("null-token.json"),7,91));
        assertThrows(IllegalArgumentException.class,()->CouponMqProtocol.publishBody(fixture("payload.json"),8,91));
        assertThrows(IllegalArgumentException.class,()->CouponMqProtocol.publishBody(fixture("payload.json"),7,92));
    }
    @Test void invalidUtf8AndSurrogate() throws Exception {
        assertThrows(IllegalArgumentException.class,()->CouponMqProtocol.canonicalPayload(new byte[]{(byte)0xc3,0x28}));
        byte[] config=fixture("config.json");String s=new String(config,StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,()->CouponMqProtocol.canonicalConfig(s.replace("event-7","\\ud800").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->CouponMqProtocol.canonicalPayload(("\ufeff"+payload()).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void utcPrecisionAndConfigRange() throws Exception {
        String s=new String(fixture("config.json"),StandardCharsets.UTF_8);
        assertArrayEquals(fixture("config.json"),CouponMqProtocol.canonicalConfig(s.replace(".000000Z","Z").getBytes(StandardCharsets.UTF_8)));
        for(String invalid:new String[]{s.replace(".000000Z",".0000000Z"),s.replace("2026-10-13","2026-02-30"),s.replace("\"settingsLockedAt\":\"2026-10-12T23:59:00.000000Z\"","\"settingsLockedAt\":null"),s.replace("\"highQuantity\":50","\"highQuantity\":101"),s.replace("\"totalQuantity\":100","\"totalQuantity\":0")})
            assertThrows(IllegalArgumentException.class,()->CouponMqProtocol.canonicalConfig(invalid.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void byteLimitIsInclusiveAndBeforeParsing() throws Exception {
        byte[] body=Arrays.copyOf(fixture("payload.json"),4096);Arrays.fill(body,fixture("payload.json").length,body.length,(byte)' ');
        assertArrayEquals(fixture("payload.json"),CouponMqProtocol.publishBody(body,7,91));
        assertThrows(IllegalArgumentException.class,()->CouponMqProtocol.publishBody(Arrays.copyOf(body,4097),7,91));
    }
}
