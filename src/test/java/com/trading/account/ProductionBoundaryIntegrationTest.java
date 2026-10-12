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
        "spring.data.redis.ssl.enabled=false", "ledger.simulated-deposits-enabled=false"
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
