package com.jinddung2.givemeticon.domain.coupon.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;
import java.nio.charset.StandardCharsets;

@Configuration
@Profile("coupon-mq")
public class CouponMqConfiguration {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CouponMqConfiguration.class);
    private final java.util.Map<String,String> diagnosticStates = new java.util.HashMap<>();
    private final java.util.Map<String,Long> diagnosticTimes = new java.util.HashMap<>();
    private final java.util.Map<String,Integer> diagnosticSuppressed = new java.util.HashMap<>();
    long diagnosticNow() { return System.nanoTime(); }
    void diagnostic(String phase, String reason, Throwable failure, String state) {
        try {
            synchronized (diagnosticStates) {
                long now = diagnosticNow();
                String kind = failure == null ? "none" : failure.getClass().getName();
                String value = reason + "|" + kind + "|" + state;
                if (value.equals(diagnosticStates.get(phase))
                        && now - diagnosticTimes.get(phase) < java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) {
                    diagnosticSuppressed.merge(phase, 1, Integer::sum); return;
                }
                int suppressed = diagnosticSuppressed.getOrDefault(phase, 0);
                diagnosticStates.put(phase, value); diagnosticTimes.put(phase, now); diagnosticSuppressed.put(phase, 0);
                LOG.info("coupon_mq diagnostic event={} pid={} phase={} reason={} failure_class={} state={} suppressed={}",
                        eventId, ProcessHandle.current().pid(), phase, reason, kind, state, suppressed);
            }
        } catch (RuntimeException ignored) { /* Observation must not affect business behavior. */ }
    }

    @Value("${coupon.mq.event-id}") private long eventId;
    @Value("${coupon.mq.host}") private String host;
    @Value("${coupon.mq.port}") private int port;
    @Value("${coupon.mq.vhost}") private String vhost;
    @Value("${coupon.mq.username}") private String username;
    @Value("${coupon.mq.password}") private String password;
    @Value("${coupon.mq.management-url}") private String managementUrl;
    private final RestTemplate management;

    public CouponMqConfiguration() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000); factory.setReadTimeout(1000);
        management = new RestTemplate(factory);
        management.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().setBasicAuth(username, password, StandardCharsets.UTF_8);
            return execution.execute(request, body);
        });
    }
    public static String exchange(long eventId) { return "coupon.mq." + eventId; }
    public static String queue(long eventId) { return exchange(eventId) + ".main"; }
    public static String parking(long eventId) { return exchange(eventId) + ".parking"; }

    private CachingConnectionFactory factory() {
        // First deployment is a single explicitly configured literal IPv4 endpoint.
        if (!host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) throw new IllegalArgumentException("MQ literal IPv4 required");
        for (String part : host.split("\\.")) if (Integer.parseInt(part) > 255) throw new IllegalArgumentException("MQ IPv4 range");
        var factory = new CachingConnectionFactory(host, port);
        factory.setUsername(username); factory.setPassword(password); factory.setVirtualHost(vhost);
        factory.getRabbitConnectionFactory().setAutomaticRecoveryEnabled(false);
        factory.getRabbitConnectionFactory().setConnectionTimeout(1000);
        factory.getRabbitConnectionFactory().setHandshakeTimeout(2000);
        factory.getRabbitConnectionFactory().setChannelRpcTimeout(1000);
        return factory;
    }
    @Bean(destroyMethod = "close") CouponMqPublisher couponMqPublisher() {
        return new CouponMqPublisher(factory()); // Dedicated, unused factory: never shared with consumers/admin.
    }
    @Bean CachingConnectionFactory couponMqConsumerFactory() { return factory(); }
    @Bean RabbitAdmin couponMqAdmin(CachingConnectionFactory couponMqConsumerFactory) {
        var admin = new RabbitAdmin(couponMqConsumerFactory); admin.setAutoStartup(false); return admin;
    }
    @Bean(destroyMethod = "close") CouponMqConsumer couponMqConsumer(CouponMqAdmissionTransactionService admission,
            CouponMqReadinessTransactionService readiness) {
        return new CouponMqConsumer(eventId, admission, readiness);
    }
    @Bean SimpleMessageListenerContainer couponMqContainer(CachingConnectionFactory couponMqConsumerFactory,
                                                           CouponMqConsumer consumer) {
        var container = new SimpleMessageListenerContainer(couponMqConsumerFactory);
        container.setQueueNames(queue(eventId)); container.setAutoStartup(false); container.setAutoDeclare(false);
        container.setConcurrentConsumers(1); container.setMaxConcurrentConsumers(1);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL); container.setPrefetchCount(50);
        container.setDeBatchingEnabled(false); container.setDefaultRequeueRejected(false);
        container.setForceStop(true); container.setShutdownTimeout(5000);
        container.setRecoveryBackOff(new org.springframework.util.backoff.FixedBackOff(5000, 0));
        container.setMessageListener(consumer); consumer.attach(container);
        return container;
    }

    /** Declare only event destinations. Policies must be provisioned and reviewed before startup. */
    public long verifyTopology(RabbitAdmin admin) {
        try { return declareAndVerify(admin); }
        catch (org.springframework.amqp.AmqpException failed) {
            for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
                if (cause instanceof com.rabbitmq.client.ShutdownSignalException shutdown
                        && shutdown.getReason() instanceof com.rabbitmq.client.AMQP.Channel.Close close
                        && close.getReplyCode() == 406) { diagnostic("rejection", "AMQP_406", failed, "rejected=true"); throw new CouponMqReadinessTransactionService.Drift(); }
            }
            throw failed;
        }
    }
    private long declareAndVerify(RabbitAdmin admin) {
        String exchange = exchange(eventId), dlx = exchange + ".dlx";
        admin.declareExchange(new DirectExchange(exchange, true, false));
        admin.declareExchange(new DirectExchange(dlx, true, false));
        Map<String,Object> mainArgs = Map.of("x-queue-type", "quorum", "x-single-active-consumer", true);
        Map<String,Object> parkingArgs = Map.of("x-queue-type", "quorum", "x-delivery-limit", -1,
                "x-max-length", 150000, "x-overflow", "reject-publish");
        admin.declareQueue(new Queue(parking(eventId), true, false, false, parkingArgs));
        admin.declareBinding(new Binding(parking(eventId), Binding.DestinationType.QUEUE, dlx, "park", null));
        admin.declareQueue(new Queue(queue(eventId), true, false, false, mainArgs));
        admin.declareBinding(new Binding(queue(eventId), Binding.DestinationType.QUEUE, exchange, "admit", null));
        if (!"4.3.6".equals(get("overview").path("rabbitmq_version").asText())) { diagnostic("version", "VERSION_MISMATCH", null, "rejected=true"); throw new CouponMqReadinessTransactionService.Drift(); }
        String base = "queues/" + segment(vhost) + "/";
        JsonNode main = get(base + segment(queue(eventId))), parking = get(base + segment(parking(eventId)));
        checkQueue(main, mainArgs); checkQueue(parking, parkingArgs);
        var expectedPolicy = new ObjectMapper().valueToTree(Map.of(
                "dead-letter-exchange", dlx, "dead-letter-routing-key", "park", "dead-letter-strategy", "at-least-once",
                "overflow", "reject-publish", "delivery-limit", 20, "max-length", 150000,
                "delayed-retry-type", "disabled", "consumer-timeout", 1800000));
        JsonNode mainPolicy = main.path("effective_policy_definition"), parkingPolicy = parking.path("effective_policy_definition");
        if ((!mainPolicy.isMissingNode() && (!mainPolicy.isObject() || !expectedPolicy.equals(mainPolicy)))
                || (!parkingPolicy.isMissingNode() && (!parkingPolicy.isObject() || !parkingPolicy.isEmpty()))) {
            try { diagnostic("policy", "EFFECTIVE_POLICY_MISMATCH", null, policyDiagnosticState(main, parking, expectedPolicy)); }
            catch (RuntimeException ignored) { /* Formatting cannot replace the original Drift. */ }
            throw new CouponMqReadinessTransactionService.Drift();
        }
        if (mainPolicy.isMissingNode() || parkingPolicy.isMissingNode()) {
            try { diagnostic("policy", "EFFECTIVE_POLICY_PREPARATION_INCOMPLETE", null, policyDiagnosticState(main, parking, expectedPolicy)); }
            catch (RuntimeException ignored) { /* Formatting cannot replace the original NotReady. */ }
            throw new CouponMqReadinessTransactionService.NotReady();
        }
        verifyBinding(exchange, queue(eventId), "admit"); verifyBinding(dlx, parking(eventId), "park");
        if (!parking.path("messages").isIntegralNumber() || parking.path("messages").asLong() < 0)
            { diagnostic("parking", "PARKING_COUNT_INVALID", null, "rejected=true"); throw new CouponMqReadinessTransactionService.NotReady(); }
        return parking.path("messages").asLong();
    }
    String policyDiagnosticState(JsonNode main, JsonNode parking, JsonNode expected) {
        var json = new ObjectMapper();
        var state = json.createObjectNode();
        state.put("main_match", expected.equals(main.path("effective_policy_definition")));
        state.put("parking_empty", parking.path("effective_policy_definition").isObject()
                && parking.path("effective_policy_definition").isEmpty());
        state.set("expected_policy", expected);
        state.set("main", policyProjection(main, "main", queue(eventId), expected, json));
        state.set("parking", policyProjection(parking, "parking", parking(eventId), expected, json));
        String value = state.toString();
        return value.length() <= 4096 ? value : "{\"projection\":\"UNKNOWN\",\"reason\":\"POLICY_PROJECTION_BOUND\"}";
    }
    private JsonNode policyProjection(JsonNode queue, String role, String name, JsonNode expected, ObjectMapper json) {
        var result = json.createObjectNode(); result.put("role", role); result.put("name", name);
        var actual = queue.path("effective_policy_definition");
        var policy = result.putObject("effective_policy_definition");
        policy.put("present", !actual.isMissingNode()); policy.put("type", actual.getNodeType().name());
        if (actual.isContainerNode()) policy.put("count", actual.size());
        if (actual.isObject()) {
            int extra = 0; var names = actual.fieldNames();
            while (names.hasNext()) if (!expected.has(names.next())) extra++;
            policy.put("unexpected_key_count", extra);
            var fields = policy.putObject("fields"); var keys = expected.fieldNames();
            while (keys.hasNext()) {
                String key = keys.next(); var value = actual.path(key); var field = fields.putObject(key);
                field.put("present", !value.isMissingNode()); field.put("type", value.getNodeType().name());
                if (value.isNull() || value.isBoolean()) field.set("value", value);
                else if (value.isTextual()) {
                    if (value.equals(expected.path(key))) field.put("value", value.textValue());
                    else { field.put("value", "[REDACTED]"); field.put("length", value.textValue().length()); }
                } else if (value.isNumber()) {
                    String number = value.asText();
                    if (number.length() <= 32) field.set("value", value);
                    else { field.put("value", "[BOUNDED]"); field.put("length", number.length()); }
                } else if (value.isContainerNode()) field.put("count", value.size());
            }
        } else if (actual.isTextual()) { policy.put("value", "[REDACTED]"); policy.put("length", actual.textValue().length()); }
        else if (actual.isBoolean() || actual.isNull()) policy.set("value", actual);
        else if (actual.isNumber()) {
            String number = actual.asText();
            if (number.length() <= 32) policy.set("value", actual);
            else { policy.put("value", "[BOUNDED]"); policy.put("length", number.length()); }
        }
        return result;
    }
    private void checkQueue(JsonNode queue, Map<String,Object> args) {
        if (!queue.path("durable").asBoolean() || queue.path("auto_delete").asBoolean()
                || queue.path("exclusive").asBoolean() || !"quorum".equals(queue.path("type").asText())
                ) { diagnostic("queue_attributes", "QUEUE_ATTRIBUTES_MISMATCH", null, "rejected=true"); throw new CouponMqReadinessTransactionService.Drift(); }
        if (!new ObjectMapper().valueToTree(args).equals(queue.path("arguments"))) { diagnostic("queue_arguments", "QUEUE_ARGUMENTS_MISMATCH", null, "rejected=true"); throw new CouponMqReadinessTransactionService.Drift(); }
        if (!queue.path("operator_policy").asText("").isEmpty()) { diagnostic("operator_policy", "OPERATOR_POLICY_MISMATCH", null, "rejected=true"); throw new CouponMqReadinessTransactionService.Drift(); }
    }
    private void verifyBinding(String exchange, String queue, String key) {
        JsonNode bindings = get("exchanges/" + segment(vhost) + "/" + segment(exchange) + "/bindings/source");
        if (!bindings.isArray() || bindings.size() != 1 || !queue.equals(bindings.get(0).path("destination").asText())
                || !"queue".equals(bindings.get(0).path("destination_type").asText())
                || !key.equals(bindings.get(0).path("routing_key").asText())) { diagnostic("binding", "BINDING_MISMATCH", null, "rejected=true"); throw new CouponMqReadinessTransactionService.Drift(); }
    }
    private JsonNode get(String path) {
        return management.getForObject(URI.create(managementUrl + "/api/" + path), JsonNode.class);
    }
    private static String segment(String text) { return UriUtils.encodePathSegment(text, StandardCharsets.UTF_8); }
}
