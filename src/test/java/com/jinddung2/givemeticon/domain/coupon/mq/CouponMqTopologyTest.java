package com.jinddung2.givemeticon.domain.coupon.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CouponMqTopologyTest {
    final ObjectMapper json=new ObjectMapper();
    final CouponMqConfiguration configuration=new CouponMqConfiguration();
    final RabbitAdmin admin=mock(RabbitAdmin.class);
    MockRestServiceServer server() {
        ReflectionTestUtils.setField(configuration,"eventId",7L);ReflectionTestUtils.setField(configuration,"vhost","isolated");
        ReflectionTestUtils.setField(configuration,"managementUrl","http://127.0.0.1:29999");
        ReflectionTestUtils.setField(configuration,"username","fixture");ReflectionTestUtils.setField(configuration,"password","fixture");
        return MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(configuration,"management")).build();
    }
    Map<String,Object> policy(){return Map.of("dead-letter-exchange","coupon.mq.7.dlx","dead-letter-routing-key","park",
        "dead-letter-strategy","at-least-once","overflow","reject-publish","delivery-limit",20,"max-length",150000,
        "delayed-retry-type","disabled","consumer-timeout",1800000);}
    String queue(Map<String,Object> args,Map<String,Object> policy)throws Exception {
        return json.writeValueAsString(Map.of("durable",true,"auto_delete",false,"exclusive",false,"type","quorum",
            "arguments",args,"effective_policy_definition",policy,"operator_policy","","messages",0));
    }
    @Test void effectiveTopologyAndPoliciesMustMatchBeforeReady()throws Exception {
        var server=server();server.expect(requestTo("http://127.0.0.1:29999/api/overview")).andRespond(withSuccess("{\"rabbitmq_version\":\"4.3.6\"}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://127.0.0.1:29999/api/queues/isolated/coupon.mq.7.main")).andRespond(withSuccess(queue(Map.of("x-queue-type","quorum","x-single-active-consumer",true),policy()),MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://127.0.0.1:29999/api/queues/isolated/coupon.mq.7.parking")).andRespond(withSuccess(queue(Map.of("x-queue-type","quorum","x-delivery-limit",-1,"x-max-length",150000,"x-overflow","reject-publish"),Map.of()),MediaType.APPLICATION_JSON));
        for(var pair:List.of(new String[]{"coupon.mq.7","coupon.mq.7.main","admit"},new String[]{"coupon.mq.7.dlx","coupon.mq.7.parking","park"})) {
            server.expect(requestTo("http://127.0.0.1:29999/api/exchanges/isolated/"+pair[0]+"/bindings/source"))
                .andRespond(withSuccess(json.writeValueAsString(List.of(Map.of("destination",pair[1],"destination_type","queue","routing_key",pair[2]))),MediaType.APPLICATION_JSON));
        }
        assertEquals(0,configuration.verifyTopology(admin));server.verify();
        verify(admin,times(2)).declareQueue(any());verify(admin,times(2)).declareBinding(any());
    }
    @Test void missingAtLeastOnceOrUnexpectedPolicyFailsClosed()throws Exception {
        var server=server();server.expect(anything()).andRespond(withSuccess("{\"rabbitmq_version\":\"4.3.6\"}",MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(queue(Map.of("x-queue-type","quorum","x-single-active-consumer",true),Map.of()),MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(queue(Map.of("x-queue-type","quorum","x-delivery-limit",-1,"x-max-length",150000,"x-overflow","reject-publish"),Map.of()),MediaType.APPLICATION_JSON));
        assertThrows(CouponMqReadinessTransactionService.Drift.class,()->configuration.verifyTopology(admin));server.verify();
    }
    @Test void onlyExact436PassesTheVersionGate() {
        for (String version : List.of("4.3.0", "4.3.1", "4.3.5", "4.3.7", "4.4.0", "4.3.6+suffix", "")) {
            var server=server();server.expect(anything()).andRespond(withSuccess(
                "{\"rabbitmq_version\":\""+version+"\"}",MediaType.APPLICATION_JSON));
            assertThrows(CouponMqReadinessTransactionService.Drift.class,()->configuration.verifyTopology(admin),version);
            server.verify();
        }
    }
    @Test void delayedRetryDisabledAndExactPolicyRemainRequired()throws Exception {
        for (String change : List.of("missing", "enabled", "extra")) {
            var altered=new HashMap<String,Object>(policy());
            if (change.equals("missing")) altered.remove("delayed-retry-type");
            if (change.equals("enabled")) altered.put("delayed-retry-type","linear");
            if (change.equals("extra")) altered.put("message-ttl",1);
            var server=server();server.expect(anything()).andRespond(withSuccess("{\"rabbitmq_version\":\"4.3.6\"}",MediaType.APPLICATION_JSON));
            server.expect(anything()).andRespond(withSuccess(queue(Map.of("x-queue-type","quorum","x-single-active-consumer",true),altered),MediaType.APPLICATION_JSON));
            server.expect(anything()).andRespond(withSuccess(queue(Map.of("x-queue-type","quorum","x-delivery-limit",-1,"x-max-length",150000,"x-overflow","reject-publish"),Map.of()),MediaType.APPLICATION_JSON));
            assertThrows(CouponMqReadinessTransactionService.Drift.class,()->configuration.verifyTopology(admin),change);server.verify();
        }
    }

    @Test void queueRejectionReasonsRemainStaticAndThrowOriginalDriftType()throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CouponMqConfiguration.class);
        var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();logs.start();logger.addAppender(logs);
        try {
            Map<String,Object> expected=Map.of("x-queue-type","quorum");
            for(String change:List.of("attributes","arguments","operator")) {
                var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(queue(expected,Map.of()));
                if(change.equals("attributes"))node.put("durable",false);
                if(change.equals("arguments"))node.putObject("arguments").put("secret-password","never-log");
                if(change.equals("operator"))node.put("operator_policy","secret-token");
                assertThrows(CouponMqReadinessTransactionService.Drift.class,()->ReflectionTestUtils.invokeMethod(configuration,"checkQueue",node,expected));
            }
            String text=logs.list.stream().map(e->e.getFormattedMessage()).collect(java.util.stream.Collectors.joining("\n"));
            for(String reason:List.of("QUEUE_ATTRIBUTES_MISMATCH","QUEUE_ARGUMENTS_MISMATCH","OPERATOR_POLICY_MISMATCH"))assertTrue(text.contains(reason));
            assertFalse(text.contains("secret-"));assertFalse(text.contains("never-log"));
        } finally {logger.detachAppender(logs);logs.stop();}
    }
    @Test void amqp406IsDiagnosedWithoutLoggingServerTextOrChangingException() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CouponMqConfiguration.class);
        var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();logs.start();logger.addAppender(logs);
        try {
            var close=new com.rabbitmq.client.AMQP.Channel.Close.Builder().replyCode(406).replyText("secret-server-message").classId(50).methodId(10).build();
            var shutdown=new com.rabbitmq.client.ShutdownSignalException(false,false,close,null);
            doThrow(new org.springframework.amqp.AmqpIOException(new java.io.IOException(shutdown))).when(admin).declareExchange(any());
            assertThrows(CouponMqReadinessTransactionService.Drift.class,()->configuration.verifyTopology(admin));
            assertTrue(logs.list.stream().anyMatch(e->e.getFormattedMessage().contains("AMQP_406")));
            assertTrue(logs.list.stream().noneMatch(e->e.getFormattedMessage().contains("secret-server-message")||e.getThrowableProxy()!=null));
        } finally {logger.detachAppender(logs);logs.stop();}
    }

    @Test void policyProjectionDistinguishesMissingNullEmptyTypesAndConflictWithoutDumpingValues()throws Exception {
        var main=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(queue(Map.of("x-queue-type","quorum","x-single-active-consumer",true),Map.of()));
        var parking=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(queue(Map.of("x-queue-type","quorum"),Map.of()));
        var expected=json.valueToTree(policy());
        for (String shape:List.of("missing","null","empty","string","array","conflict")) {
            if(shape.equals("missing"))main.remove("effective_policy_definition");
            if(shape.equals("null"))main.putNull("effective_policy_definition");
            if(shape.equals("empty"))main.putObject("effective_policy_definition");
            if(shape.equals("string"))main.put("effective_policy_definition","secret-password");
            if(shape.equals("array"))main.putArray("effective_policy_definition").add("secret-password");
            if(shape.equals("conflict"))main.set("effective_policy_definition",json.valueToTree(Map.of("delivery-limit",19,"secret-key","secret-password")));
            parking.set("effective_policy_definition",main.path("effective_policy_definition"));
            if(shape.equals("missing"))parking.remove("effective_policy_definition");
            String text=configuration.policyDiagnosticState(main,parking,expected);assertTrue(text.length()<=4096);assertFalse(text.contains("secret-password"));assertFalse(text.contains("secret-key"));
            var projected=json.readTree(text);var actual=projected.path("main").path("effective_policy_definition");
            assertEquals(!shape.equals("missing"),actual.path("present").asBoolean());
            String type=switch(shape){case "missing"->"MISSING";case "null"->"NULL";case "string"->"STRING";case "array"->"ARRAY";default->"OBJECT";};
            assertEquals(type,actual.path("type").asText());assertFalse(projected.path("main_match").asBoolean());
            if(type.equals("OBJECT"))assertEquals(8,actual.path("fields").size());
            if(shape.equals("empty"))assertEquals(0,actual.path("count").asInt());
            if(shape.equals("conflict")){assertEquals(1,actual.path("unexpected_key_count").asInt());assertEquals(19,actual.path("fields").path("delivery-limit").path("value").asInt());}
        }
    }
    @Test void policyProjectionBoundsStringsAndNestedDataAndRetainsExpectedPolicy()throws Exception {
        var main=json.createObjectNode();var actual=main.putObject("effective_policy_definition");
        for(String key:policy().keySet())actual.put(key,"secret".repeat(10000));
        actual.putObject("secret-extra").put("password","secret");
        var parking=json.createObjectNode();parking.putArray("effective_policy_definition").add("secret".repeat(10000));
        String text=configuration.policyDiagnosticState(main,parking,json.valueToTree(policy()));
        assertTrue(text.length()<=4096);assertFalse(text.contains("secret"));var projected=json.readTree(text);
        assertEquals(json.valueToTree(policy()),projected.path("expected_policy"));
        assertEquals(1,projected.path("main").path("effective_policy_definition").path("unexpected_key_count").asInt());
        assertEquals("ARRAY",projected.path("parking").path("effective_policy_definition").path("type").asText());
    }
    @Test void mismatchUsesOnlyFetchedResponsesAndFormattingFailureStillThrowsDrift()throws Exception {
        for(boolean formattingFailure:List.of(false,true)) {
            var original=new CouponMqConfiguration();ReflectionTestUtils.setField(original,"eventId",7L);ReflectionTestUtils.setField(original,"vhost","isolated");ReflectionTestUtils.setField(original,"managementUrl","http://127.0.0.1:29999");ReflectionTestUtils.setField(original,"username","fixture");ReflectionTestUtils.setField(original,"password","fixture");var c=spy(original);
            if(formattingFailure)doThrow(new IllegalStateException("secret-format-error")).when(c).policyDiagnosticState(any(),any(),any());
            var server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(c,"management")).build();
            server.expect(anything()).andRespond(withSuccess("{\"rabbitmq_version\":\"4.3.6\"}",MediaType.APPLICATION_JSON));
            server.expect(anything()).andRespond(withSuccess(queue(Map.of("x-queue-type","quorum","x-single-active-consumer",true),Map.of()),MediaType.APPLICATION_JSON));
            server.expect(anything()).andRespond(withSuccess(queue(Map.of("x-queue-type","quorum","x-delivery-limit",-1,"x-max-length",150000,"x-overflow","reject-publish"),Map.of()),MediaType.APPLICATION_JSON));
            assertThrows(CouponMqReadinessTransactionService.Drift.class,()->c.verifyTopology(admin));server.verify();
        }
    }

    void expectPolicies(MockRestServiceServer server, String mainPolicy, String parkingPolicy, boolean bindings) throws Exception {
        server.expect(anything()).andRespond(withSuccess("{\"rabbitmq_version\":\"4.3.6\"}",MediaType.APPLICATION_JSON));
        var main=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(queue(Map.of("x-queue-type","quorum","x-single-active-consumer",true),policy()));
        var parking=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(queue(Map.of("x-queue-type","quorum","x-delivery-limit",-1,"x-max-length",150000,"x-overflow","reject-publish"),Map.of()));
        if(mainPolicy==null)main.remove("effective_policy_definition");else main.set("effective_policy_definition",json.readTree(mainPolicy));
        if(parkingPolicy==null)parking.remove("effective_policy_definition");else parking.set("effective_policy_definition",json.readTree(parkingPolicy));
        server.expect(anything()).andRespond(withSuccess(main.toString(),MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(parking.toString(),MediaType.APPLICATION_JSON));
        if(bindings)for(var pair:List.of(new String[]{"coupon.mq.7","coupon.mq.7.main","admit"},new String[]{"coupon.mq.7.dlx","coupon.mq.7.parking","park"}))
            server.expect(requestTo("http://127.0.0.1:29999/api/exchanges/isolated/"+pair[0]+"/bindings/source"))
                .andRespond(withSuccess(json.writeValueAsString(List.of(Map.of("destination",pair[1],"destination_type","queue","routing_key",pair[2]))),MediaType.APPLICATION_JSON));
    }
    @Test void onlyAbsentWholePolicyFieldsArePreparationIncomplete() throws Exception {
        for(String[] pair:new String[][]{{null,"{}"},{json.writeValueAsString(policy()),null},{null,null}}) {
            var server=server();expectPolicies(server,pair[0],pair[1],false);
            assertThrows(CouponMqReadinessTransactionService.NotReady.class,()->configuration.verifyTopology(admin));server.verify();
        }
    }
    @Test void presentInvalidPolicyAlwaysWinsOverTheOtherMissingField() throws Exception {
        for(String invalid:List.of("null","[]","true","0","\"secret\"","{}","{\"delivery-limit\":20}")) {
            var server=server();expectPolicies(server,invalid,null,false);
            assertThrows(CouponMqReadinessTransactionService.Drift.class,()->configuration.verifyTopology(admin));server.verify();
        }
        for(String invalid:List.of("null","[]","false","0","\"secret\"","{\"delivery-limit\":20}")) {
            var server=server();expectPolicies(server,null,invalid,false);
            assertThrows(CouponMqReadinessTransactionService.Drift.class,()->configuration.verifyTopology(admin));server.verify();
        }
        for(String invalid:List.of("null","[]","true","0","\"secret\"")) {
            var server=server();expectPolicies(server,json.writeValueAsString(policy()),invalid,false);
            assertThrows(CouponMqReadinessTransactionService.Drift.class,()->configuration.verifyTopology(admin));server.verify();
        }
        for(String key:policy().keySet()) {
            var incomplete=new HashMap<>(policy());incomplete.remove(key);var server=server();expectPolicies(server,json.writeValueAsString(incomplete),null,false);
            assertThrows(CouponMqReadinessTransactionService.Drift.class,()->configuration.verifyTopology(admin));server.verify();
        }
        for(Object value:List.of(20.0,"20",19)) {
            var conflict=new HashMap<>(policy());conflict.put("delivery-limit",value);var server=server();expectPolicies(server,json.writeValueAsString(conflict),null,false);
            assertThrows(CouponMqReadinessTransactionService.Drift.class,()->configuration.verifyTopology(admin));server.verify();
        }
        var extra=new HashMap<>(policy());extra.put("unexpected",true);var server=server();expectPolicies(server,json.writeValueAsString(extra),null,false);
        assertThrows(CouponMqReadinessTransactionService.Drift.class,()->configuration.verifyTopology(admin));server.verify();
    }
    @Test void missingPolicyHasDistinctBoundedReasonAndFormattingCannotReplaceNotReady() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CouponMqConfiguration.class);
        var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();logs.start();logger.addAppender(logs);
        try {
            var server=server();expectPolicies(server,null,"{}",false);
            assertThrows(CouponMqReadinessTransactionService.NotReady.class,()->configuration.verifyTopology(admin));server.verify();
            String text=logs.list.stream().map(e->e.getFormattedMessage()).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(text.contains("EFFECTIVE_POLICY_PREPARATION_INCOMPLETE"));assertFalse(text.contains("EFFECTIVE_POLICY_MISMATCH"));
            assertTrue(text.contains("\"present\":false"));assertTrue(text.length()<5000);assertTrue(logs.list.stream().allMatch(e->e.getThrowableProxy()==null));
            server=server();expectPolicies(server,null,"{}",false);var failing=spy(configuration);
            doThrow(new IllegalStateException("secret-format-error")).when(failing).policyDiagnosticState(any(),any(),any());
            assertThrows(CouponMqReadinessTransactionService.NotReady.class,()->failing.verifyTopology(admin));server.verify();
        } finally {logger.detachAppender(logs);logs.stop();}
    }

}
