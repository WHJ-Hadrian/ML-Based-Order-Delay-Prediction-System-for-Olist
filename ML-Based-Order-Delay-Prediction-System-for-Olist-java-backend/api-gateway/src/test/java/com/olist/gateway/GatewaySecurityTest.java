package com.olist.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewaySecurityTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void exposesMetricsWithoutAuthentication() {
        webTestClient.get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void protectsApiPaths() {
        webTestClient.get()
                .uri("/api/v1/orders")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }
}
