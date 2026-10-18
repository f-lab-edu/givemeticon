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

}
