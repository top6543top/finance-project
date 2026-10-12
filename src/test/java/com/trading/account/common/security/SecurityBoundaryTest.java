package com.trading.account.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Map;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

class SecurityBoundaryTest {
    @Test void onlyExactGetProbesAreAnonymousAndOperationalDetailsStayProtected() throws Exception {
        try (var context = context(false)) {
            MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
            for (String path : new String[]{"/livez", "/readyz"}) {
                mvc.perform(get(path)).andExpect(status().isOk());
                mvc.perform(post(path)).andExpect(status().isUnauthorized());
            }
            for (String path : new String[]{"/livez/details", "/readyz/details", "/actuator/health", "/actuator/health/db",
                    "/actuator/prometheus", "/swagger-ui/index.html", "/v3/api-docs"}) {
                mvc.perform(get(path)).andExpect(status().isUnauthorized());
            }
        }
    }

    @Test void explicitDevelopmentConfigurationCanExposeApiDocs() throws Exception {
        try (var context = context(true)) {
            MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
            mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
        }
    }

    @Test void metricsAreAnonymousOnlyOnDedicatedManagementPort() throws Exception {
        try (var context = context(false)) {
            MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
            mvc.perform(get("/actuator/prometheus").with(request -> { request.setLocalPort(9090); return request; }))
                    .andExpect(status().isOk());
            mvc.perform(get("/actuator/prometheus").with(request -> { request.setLocalPort(8080); return request; }))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/actuator/health/db").with(request -> { request.setLocalPort(9090); return request; }))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/actuator/prometheus").with(request -> { request.setLocalPort(9090); return request; }))
                    .andExpect(status().isUnauthorized());
        }
    }

    private AnnotationConfigWebApplicationContext context(boolean publicDocs) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("app.security.public-docs", publicDocs, "management.server.port", "9090")));
        context.register(TestConfiguration.class);
        context.refresh();
        return context;
    }

    @Configuration @EnableWebMvc @EnableWebSecurity @Import(SecurityConfig.class)
    static class TestConfiguration {
        @Bean JwtTokenProvider tokenProvider() { return new JwtTokenProvider("test-secret-for-security-at-least-32-bytes", 60000); }
        @Bean JwtAuthenticationEntryPoint entryPoint() { return new JwtAuthenticationEntryPoint(new ObjectMapper()); }
        @Bean Endpoints endpoints() { return new Endpoints(); }
    }

    @RestController static class Endpoints {
        @GetMapping({"/livez", "/readyz", "/actuator/health", "/actuator/health/db", "/actuator/prometheus", "/v3/api-docs"})
        String endpoint() { return "ok"; }
    }
}
