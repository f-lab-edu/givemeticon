package com.jinddung2.givemeticon.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinddung2.givemeticon.common.config.controller.HandlerAdapterCustomConfig;
import com.jinddung2.givemeticon.domain.coupon.scheduler.*;
import com.jinddung2.givemeticon.domain.coupon.service.*;
import com.jinddung2.givemeticon.domain.coupon.facade.CreateCouponFacade;
import com.jinddung2.givemeticon.domain.coupon.mapper.CouponApplicationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Real embedded HTTP/Actuator/MVC boundary; raw responses stay in memory only. */
class WarmedConditionsHttpTest {
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration.class,
        org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration.class,
        org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class,
        org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration.class,
        org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration.class,
        org.springframework.boot.actuate.autoconfigure.condition.ConditionsReportEndpointAutoConfiguration.class,
        org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration.class,
        org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration.class,
        org.springframework.boot.actuate.autoconfigure.endpoint.web.ServletEndpointManagementContextConfiguration.class,
        org.springframework.boot.actuate.autoconfigure.endpoint.web.servlet.WebMvcEndpointManagementContextConfiguration.class
    })
    @ComponentScan(basePackageClasses = CouponEventIssuanceWorker.class)
    @Import(CustomHandler.class)
    static class HttpConfig {
        @Bean CouponIssueRequestService requests() { return mock(CouponIssueRequestService.class); }
        @Bean CreateCouponFacade facade() { return mock(CreateCouponFacade.class); }
        @Bean CouponApplicationMapper applications() { return mock(CouponApplicationMapper.class); }
        @Bean CouponIssuanceProcessor processor() { return mock(CouponIssuanceProcessor.class); }
    }
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "diagnosis.custom-handler", havingValue = "true")
    @Import(HandlerAdapterCustomConfig.class)
    static class CustomHandler {}

    @Test void actualHttpResponseThroughApplicationHandlerAndSamePythonParser() throws Exception {
        var mapper = new ObjectMapper();
        var results = mapper.createArrayNode();
        for (String path : new String[]{"stock", "v1"}) {
            for (boolean custom : new boolean[]{false, true}) {
                String profiles = path.equals("stock") ? "verifier-loadtest,mysql-loadtest,redis-lock-loadtest"
                    : "verifier-loadtest,coupon-admission,coupon-admission-test,coupon-admission-batch,coupon-issuance,coupon-issuance-batch";
                try (var context = new SpringApplicationBuilder(HttpConfig.class).web(WebApplicationType.SERVLET)
                    .run("--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=ERROR",
                        "--spring.profiles.active=" + profiles,
                        "--spring.config.additional-location=" + Path.of("scripts/verifier-isolated/verifier-loadtest-config.yml").toAbsolutePath().toUri(),
                        "--LOADTEST_DB_PASSWORD=dummy", "--coupon.issue-worker.mode=off", "--coupon.issue-worker.enabled=false",
                        "--management.endpoints.web.exposure.include=conditions", "--diagnosis.custom-handler=" + custom)) {
                    int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                    var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/actuator/conditions")).GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(200);
                    var tree = mapper.readTree(response.body());
                    boolean envelope = tree.path("message").asText().equals("SUCCESS") && tree.path("data").isObject();
                    boolean topContexts = tree.path("contexts").isObject();
                    var report = envelope ? tree.path("data") : tree;
                    assertThat(report.path("contexts").isObject()).isTrue();
                    // Only fixed booleans/counts leave the Python child, never raw report/key/env.
                    String script = "import importlib.util,json,sys; "
                        + "s=importlib.util.spec_from_file_location('policy','scripts/verifier-isolated/hosted/worker-meter-policy.py'); "
                        + "m=importlib.util.module_from_spec(s); s.loader.exec_module(m); d=json.load(sys.stdin); "
                        + "report,unwrapped=m.normalize_conditions_response(d); r=m.conditions(report,sys.argv[1]); "
                        + "print(json.dumps({'pass':r['pass'],'envelope_unwrapped':unwrapped,"
                        + "'counts':{k:{c:v[c] for c in ('pos_count','neg_count')} for k,v in r['selected_conditions'].items()}}))";
                    var process = new ProcessBuilder("python3", "-c", script, path).start();
                    try (var stdin = process.getOutputStream()) { stdin.write(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                    String safeOutput = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    assertThat(process.waitFor()).isZero();
                    var verdict = mapper.readTree(safeOutput);
                    assertThat(verdict.path("pass").asBoolean()).isTrue();
                    assertThat(envelope).isEqualTo(custom);
                    assertThat(topContexts).isEqualTo(!custom);
                    assertThat(verdict.path("envelope_unwrapped").asBoolean()).isEqualTo(custom);
                    for (String worker : new String[]{"CouponIssueAsyncWorker", "CouponBatchIssueWorker",
                            "CouponIssueRequestRecoveryScheduler", "CouponEventIssuanceWorker"}) {
                        boolean present = worker.equals("CouponEventIssuanceWorker") && path.equals("v1");
                        assertThat(verdict.path("counts").path(worker).path("pos_count").asInt()).isEqualTo(present ? 1 : 0);
                        assertThat(verdict.path("counts").path(worker).path("neg_count").asInt()).isEqualTo(present ? 0 : 1);
                    }
                    var result = results.addObject();
                    result.put("path", path); result.put("custom_handler", custom);
                    result.put("envelope_applied", envelope); result.put("top_level_contexts", topContexts);
                    result.put("data_contexts", tree.path("data").path("contexts").isObject());
                    result.set("parser", verdict);
                }
            }
        }
        Files.writeString(Path.of("build/conditions-http-safe.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }
}
