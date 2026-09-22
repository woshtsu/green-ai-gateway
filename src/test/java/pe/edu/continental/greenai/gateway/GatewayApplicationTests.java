package pe.edu.continental.greenai.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationTests {

    private static final DisposableServer MONITORING = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> response
                    .header(RequestIdFilter.HEADER, request.requestHeaders().get(RequestIdFilter.HEADER))
                    .sendString(reactor.core.publisher.Mono.just(request.uri())))
            .bindNow();

    @DynamicPropertySource
    static void monitoringUrl(DynamicPropertyRegistry properties) {
        properties.add("GATEWAY_MONITORING_BASE_URL",
                () -> "http://127.0.0.1:" + MONITORING.port());
    }

    @AfterAll
    static void stopMonitoring() {
        MONITORING.disposeNow();
    }

    @Autowired
    private RouteLocator routeLocator;

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void connectToGateway() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @Test
    void loadsOnlyTheConfirmedMonitoringRoute() {
        var routeIds = routeLocator.getRoutes().map(route -> route.getId()).collectList().block();
        assertThat(routeIds).containsExactly("monitoring-metrics");
    }

    @Test
    void unknownRoutesAreNotForwardedAndReceiveARequestId() {
        client.get()
                .uri("/api/processing/v1/analytics/summary")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueMatches(RequestIdFilter.HEADER, "[A-Za-z0-9._:-]{1,128}");
    }

    @Test
    void preservesSafeCallerRequestId() {
        client.get()
                .uri("/does-not-exist")
                .header(RequestIdFilter.HEADER, "frontend-request-42")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals(RequestIdFilter.HEADER, "frontend-request-42");
    }

    @Test
    void rewritesConfirmedRouteAndPreservesQueryAndRequestId() {
        client.get()
                .uri("/api/monitoring/v1/metrics/current?metric=node.cpu.utilization&resourceType=node")
                .header(RequestIdFilter.HEADER, "integration-42")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(RequestIdFilter.HEADER, "integration-42")
                .expectBody(String.class)
                .isEqualTo("/api/v1/metrics/current?metric=node.cpu.utilization&resourceType=node");
    }
}
