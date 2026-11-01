package com.trading.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

// Loads src/main/resources exactly as the prod pod does (application.yaml → common import → prod profile),
// because test resources shadow application.yaml and integration tests import common inline.
class ProdConfigPrecedenceTest {
    @Configuration static class Empty {}

    @Test
    void prodProfileTrustsForwardedHeadersOnlyFromConfiguredProxies() {
        try (var ctx = new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE)
                .properties("spring.config.location=file:src/main/resources/", "SPRING_PROFILES_ACTIVE=prod",
                        "TRUSTED_PROXIES=10.42.x.x", "REDIS_HOST=unused").run()) {
            Environment env = ctx.getEnvironment();
            assertThat(env.getProperty("server.forward-headers-strategy")).isEqualTo("native");
            assertThat(env.getProperty("server.tomcat.remoteip.internal-proxies")).isEqualTo("10.42.x.x");
        }
    }

    @Test
    void devProfileIgnoresForwardedHeaders() {
        try (var ctx = new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE)
                .properties("spring.config.location=file:src/main/resources/", "SPRING_PROFILES_ACTIVE=dev").run()) {
            assertThat(ctx.getEnvironment().getProperty("server.forward-headers-strategy")).isEqualTo("none");
        }
    }
}
