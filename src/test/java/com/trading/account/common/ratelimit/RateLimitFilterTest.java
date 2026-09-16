package com.trading.account.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private TokenBucketRateLimiter rateLimiter;

    @Mock
    private FilterChain filterChain;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(rateLimiter, new ObjectMapper());
    }

    @Test
    void 토큰이_남아있으면_체인을_그대로_통과시킨다() throws Exception {
        when(rateLimiter.tryConsume("127.0.0.1")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void 토큰이_없으면_429와_구조화된_에러응답을_반환하고_체인을_막는다() throws Exception {
        when(rateLimiter.tryConsume("127.0.0.1")).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("\"code\":\"C016\"");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void actuator_경로는_필터를_건너뛴다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/actuator/health");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void XForwardedFor_헤더가_있으면_첫번째_IP를_클라이언트_키로_쓴다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        request.setRemoteAddr("10.0.0.1");

        assertThat(filter.resolveClientKey(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void XForwardedFor_헤더가_없으면_remoteAddr를_쓴다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.0.10");

        assertThat(filter.resolveClientKey(request)).isEqualTo("192.168.0.10");
    }
}
