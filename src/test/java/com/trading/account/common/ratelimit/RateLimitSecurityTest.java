package com.trading.account.common.ratelimit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RateLimitSecurityTest {
    @Test void differentForwardedHeadersShareTheSocketPeersLoginBudget() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(Object[].class))).thenAnswer(invocation ->
                invocation.getArgument(1).equals(java.util.List.of("login:10.0.0.7")) ? 0L : 1L);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(redis, "login:", new RateLimitProperties.Bucket(1, 1, 60));
        RateLimitFilter filter = new RateLimitFilter(limiter, limiter, new ObjectMapper());
        AtomicInteger passed = new AtomicInteger();
        for (String forwarded : new String[]{"203.0.113.1", "203.0.113.2, 10.0.0.8", "not-an-ip"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
            request.setRemoteAddr("10.0.0.7");
            request.addHeader("X-Forwarded-For", forwarded);
            request.addHeader("Forwarded", "for=198.51.100.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> passed.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isNotBlank();
        }
        assertThat(passed).hasValue(0);
    }

    @Test void redisFailureStopsLoginAndGeneralRequestsWithoutLoggingSecrets() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("redis://user:secret@private:6379"));
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(redis, "login:", new RateLimitProperties.Bucket(1, 1, 60));
        RateLimitFilter filter = new RateLimitFilter(limiter, limiter, new ObjectMapper());
        Logger logger = (Logger) LoggerFactory.getLogger(TokenBucketRateLimiter.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            AtomicInteger passed = new AtomicInteger();
            for (String path : new String[]{"/api/auth/login", "/api/accounts"}) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(new MockHttpServletRequest("POST", path), response, (req, res) -> passed.incrementAndGet());
                assertThat(response.getStatus()).isEqualTo(503);
                assertThat(response.getHeader("Retry-After")).isNotBlank();
                assertThat(response.getContentAsString()).doesNotContain("secret", "private");
            }
            assertThat(passed).hasValue(0);
            for (ILoggingEvent event : logs.list) {
                assertThat(event.getFormattedMessage()).doesNotContain("secret", "private");
                assertThat(event.getThrowableProxy()).isNull();
            }
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void onlyExactHealthProbePathsSkipTheLimiter() throws Exception {
        TokenBucketRateLimiter limiter = mock(TokenBucketRateLimiter.class);
        RateLimitFilter filter = new RateLimitFilter(limiter, limiter, new ObjectMapper());
        for (String path : new String[]{"/livez", "/readyz", "/actuator/health", "/actuator/prometheus"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicInteger passed = new AtomicInteger();
            filter.doFilter(new MockHttpServletRequest("GET", path), response, (req, res) -> passed.incrementAndGet());
            assertThat(passed).as(path).hasValue(1);
        }
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator-evil"))).isFalse();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/livez/secret"))).isFalse();
    }

    @ParameterizedTest @CsvSource({"0,1,1", "1,0,1", "1,1,0", "-1,1,1", "1,-1,1", "1,1,-1"})
    void invalidBucketConfigurationIsRejectedAtStartup(int capacity, int refill, int seconds) {
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitProperties.Bucket(capacity, refill, seconds));
    }
}
