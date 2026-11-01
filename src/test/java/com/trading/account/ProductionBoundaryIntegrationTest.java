package com.trading.account;

import com.trading.account.common.security.JwtTokenProvider;
import com.trading.account.domain.account.AccountRepository;
import com.trading.account.domain.account.AccountService;
import com.trading.account.domain.member.Member;
import com.trading.account.domain.member.MemberRepository;
import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=classpath:application-common.yaml",
        "spring.flyway.enabled=true", // Install the disposable test DB using the same release migrations.
        "management.server.port=0", "reconciliation.scheduler.enabled=false",
        "spring.data.redis.ssl.enabled=false", "ledger.simulated-deposits-enabled=false",
        // This inline import outranks application-prod.yaml, unlike the real load order (ProdConfigPrecedenceTest).
        "server.forward-headers-strategy=native"
})
class ProductionBoundaryIntegrationTest extends AbstractIntegrationTest {
    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", MYSQL::getJdbcUrl);
        registry.add("DB_USERNAME", MYSQL::getUsername);
        registry.add("DB_PASSWORD", MYSQL::getPassword);
        registry.add("REDIS_HOST", REDIS::getHost);
        registry.add("REDIS_PORT", () -> REDIS.getMappedPort(6379));
        registry.add("REDIS_PASSWORD", () -> "");
        registry.add("JWT_SECRET", () -> "test-only-jwt-secret-key-not-for-production-32bytes-min");
        // TestRestTemplate connects from loopback, so it plays the trusted Traefik hop.
        registry.add("TRUSTED_PROXIES", () -> "127\\.0\\.0\\.1|0:0:0:0:0:0:0:1");
        registry.add("rate-limit.login.capacity", () -> 1);
        registry.add("rate-limit.login.refill-tokens", () -> 1);
        registry.add("rate-limit.login.refill-period-seconds", () -> 3600);
    }
    @Autowired TestRestTemplate http;
    @Autowired MemberRepository members;
    @Autowired AccountService accounts;
    @Autowired AccountRepository accountRepository;
    @Autowired JwtTokenProvider tokens;

    @Test
    void probesReachMainServerButDetailsAndMetricsStayProtected() {
        for (String path : new String[]{"/livez", "/readyz"}) {
            ResponseEntity<String> response = http.getForEntity(path,String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("UP").doesNotContain("components", "jdbc", "password");
        }
        for (String path : new String[]{"/actuator/health", "/actuator/prometheus", "/readyz/details", "/swagger-ui/index.html"}) {
            assertThat(http.getForEntity(path,String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void databaseDuplicateErrorsNeverWriteEmailToApplicationLogs() {
        String email = "private-sql-error@example.invalid";
        members.saveAndFlush(new Member("Review", email, "private-hash"));
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            // Bypass the application precheck to exercise MySQL's actual UNIQUE violation.
            assertThatThrownBy(() -> members.saveAndFlush(new Member("Review", email, "private-hash")))
                    .isInstanceOf(DataIntegrityViolationException.class);
            for (ILoggingEvent event : logs.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(email, "private-hash");
                if (event.getThrowableProxy() != null) {
                    assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain(email, "private-hash");
                }
            }
        } finally {
            root.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void loginBudgetFollowsTheClientIpAppendedByTrustedProxy() {
        String client = "203.0.113.10";
        assertThat(loginVia(client)).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(loginVia(client)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // A forged left-most value cannot buy a fresh bucket: the valve reads right-to-left.
        assertThat(loginVia("198.51.100.1, " + client)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(loginVia("198.51.100.250")).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private HttpStatusCode loginVia(String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        return http.postForEntity("/api/auth/login",
                new HttpEntity<>("{\"email\":\"nobody@example.invalid\",\"password\":\"wrong-password\"}", headers),
                String.class).getStatusCode();
    }

    @Test
    void authenticatedAccountOwnerCannotCreateSimulatedMoneyInProduction() {
        Member member = members.save(new Member("Review", "prod-boundary@example.invalid", "test-hash"));
        String account = accounts.createAccount(member.getId()).accountNumber();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokens.generateToken(member.getId(),member.getEmail()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key","prod-disabled-deposit");
        ResponseEntity<String> response = http.postForEntity("/api/accounts/"+account+"/deposit",
                new HttpEntity<>("{\"amount\":100}",headers),String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("C019");
        assertThat(accountRepository.findByAccountNumber(account).orElseThrow().getBalance()).isZero();
    }
}
