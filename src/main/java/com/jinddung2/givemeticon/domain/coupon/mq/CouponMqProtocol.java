package com.jinddung2.givemeticon.domain.coupon.mq;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Protocol v1 only. No authentication/readiness is fabricated by this codec. */
public final class CouponMqProtocol {
    private static final List<String> CONFIG = List.of("eventId", "publicId", "startsAtUtc", "totalQuantity", "highQuantity", "highPoints", "normalPoints", "settingsLockedAt");
    private static final List<String> PAYLOAD = List.of("schemaVersion", "eventId", "memberId", "attemptId", "configVersion", "openToken");
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = mapper();
    private CouponMqProtocol() { }

    private static ObjectMapper mapper() {
        JsonFactory factory = new JsonFactory();
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        factory.setCharacterEscapes(new CharacterEscapes() {
            private final int[] codes = standardAsciiEscapesForJSON();
            { for (int i=0; i<32; i++) codes[i]=ESCAPE_CUSTOM; }
            @Override public int[] getEscapeCodesForAscii() { return codes; }
            @Override public SerializableString getEscapeSequence(int ch) {
                return ch < 32 ? new SerializedString(String.format(Locale.ROOT,"\\u%04x",ch)) : null;
            }
        });
        return new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private static ObjectNode read(byte[] wire, List<String> fields) {
        if (wire == null || wire.length == 0 || wire.length > 4096) throw invalid();
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(wire)).toString();
            if (text.charAt(0)=='\ufeff') throw invalid();
            JsonNode node=MAPPER.readTree(text);
            if (!node.isObject() || node.size()!=fields.size()) throw invalid();
            for(String field:fields) if(!node.has(field)) throw invalid();
            return (ObjectNode) node;
        } catch (Exception e) { throw invalid(); } // Never include raw input in diagnostics.
    }
    public static byte[] canonicalPayload(byte[] wire) {
        ObjectNode in=read(wire,PAYLOAD), out=MAPPER.createObjectNode();
        if (integer(in,"schemaVersion",true)!=1) throw invalid();
        out.put("schemaVersion",1);out.put("eventId",integer(in,"eventId",false));out.put("memberId",integer(in,"memberId",true));
        out.put("attemptId",uuid(string(in,"attemptId")));
        String hash=string(in,"configVersion");if(!hash.matches("[0-9a-fA-F]{64}")) throw invalid();
        out.put("configVersion",hash.toLowerCase(Locale.ROOT));
        if(in.get("openToken").isNull()) out.putNull("openToken");
        else out.put("openToken",uuid(string(in,"openToken")));
        return bytes(out);
    }
    public static byte[] canonicalConfig(byte[] wire) {
        ObjectNode in=read(wire,CONFIG),out=MAPPER.createObjectNode();
        out.put("eventId",integer(in,"eventId",false));
        String publicId=string(in,"publicId");if(publicId.isEmpty() || publicId.codePointCount(0,publicId.length())>64) throw invalid();
        out.put("publicId",publicId);out.put("startsAtUtc",time(string(in,"startsAtUtc")));
        long total=integer(in,"totalQuantity",true), high=integer(in,"highQuantity",true);
        long hp=integer(in,"highPoints",true),np=integer(in,"normalPoints",true);
        if(total<=0 || high<0 || high>total || hp<0 || np<0) throw invalid();
        out.put("totalQuantity",total);out.put("highQuantity",high);out.put("highPoints",hp);out.put("normalPoints",np);
        out.put("settingsLockedAt",time(string(in,"settingsLockedAt")));
        return bytes(out);
    }
    public static byte[] publishBody(byte[] wire, long trustedEventId, int trustedMemberId) {
        byte[] canonical=canonicalPayload(wire);ObjectNode in=read(canonical,PAYLOAD);
        if(in.get("openToken").isNull() || in.get("eventId").longValue()!=trustedEventId || in.get("memberId").intValue()!=trustedMemberId) throw invalid();
        if(canonical.length>4096) throw invalid();
        return canonical;
    }
    record Payload(long eventId, int memberId, String attemptId, String configVersion, String openToken) { }
    static Payload payload(byte[] wire) {
        ObjectNode in = read(canonicalPayload(wire), PAYLOAD);
        return new Payload(in.get("eventId").longValue(), in.get("memberId").intValue(),
                in.get("attemptId").textValue(), in.get("configVersion").textValue(),
                in.get("openToken").isNull() ? null : in.get("openToken").textValue());
    }
    static byte[] payload(long eventId, int memberId, String attemptId, String version, String token) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("schemaVersion", 1); out.put("eventId", eventId); out.put("memberId", memberId);
        out.put("attemptId", attemptId); out.put("configVersion", version); out.put("openToken", token);
        return canonicalPayload(bytes(out));
    }
    static String attemptId(byte[] canonical) { return string(read(canonical,PAYLOAD),"attemptId"); }
    public static String sha256(byte[] canonical) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static byte[] bytes(ObjectNode node) {
        try { return MAPPER.writeValueAsString(node).getBytes(StandardCharsets.UTF_8); }
        catch(Exception e) { throw invalid(); }
    }
    private static long integer(ObjectNode in,String field,boolean int32) {
        JsonNode n=in.get(field);
        if(!n.isIntegralNumber() || !n.canConvertToLong() || (int32 && !n.canConvertToInt())) throw invalid();
        return n.longValue();
    }
    private static String string(ObjectNode in,String field) {
        JsonNode n=in.get(field);if(!n.isTextual()) throw invalid();String s=n.textValue();
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(i+1>=s.length() || !Character.isLowSurrogate(s.charAt(++i))) throw invalid();
            } else if(Character.isLowSurrogate(c)) throw invalid();
        }
        return s;
    }
    private static String uuid(String s) {
        if(!s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid();
        return s.toLowerCase(Locale.ROOT);
    }
    private static String time(String s) {
        if(!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z")) throw invalid();
        try { return UTC.format(LocalDateTime.parse(s.substring(0,s.length()-1), DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC)); }
        catch(Exception e) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid coupon MQ protocol v1"); }
}
