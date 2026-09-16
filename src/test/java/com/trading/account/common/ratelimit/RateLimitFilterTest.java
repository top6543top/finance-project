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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private TokenBucketRateLimiter generalRateLimiter;

    @Mock
    private TokenBucketRateLimiter loginRateLimiter;

    @Mock
    private FilterChain filterChain;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(generalRateLimiter, loginRateLimiter, new ObjectMapper());
    }

    @Test
    void 토큰이_남아있으면_체인을_그대로_통과시킨다() throws Exception {
        when(generalRateLimiter.tryConsume("127.0.0.1")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void 토큰이_없으면_429와_구조화된_에러응답을_반환하고_체인을_막는다() throws Exception {
        when(generalRateLimiter.tryConsume("127.0.0.1")).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("\"code\":\"C016\"");
        verify(filterChain, never()).doFilter(request, response);
    }

    // 로그인은 브루트포스 방지를 위해 일반 API와 다른(좁은) 버킷을 써야 하므로, 실제로
    // loginRateLimiter가 호출되고 generalRateLimiter는 안 건드리는지가 이 테스트의 핵심.
    @Test
    void 로그인_요청은_로그인_전용_버킷을_사용한다() throws Exception {
        when(loginRateLimiter.tryConsume("127.0.0.1")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(loginRateLimiter).tryConsume("127.0.0.1");
        verify(generalRateLimiter, never()).tryConsume(anyString());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void 로그인이_아닌_요청은_일반_버킷을_사용한다() throws Exception {
        when(generalRateLimiter.tryConsume("127.0.0.1")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/transfer");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(generalRateLimiter).tryConsume("127.0.0.1");
        verify(loginRateLimiter, never()).tryConsume(anyString());
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
