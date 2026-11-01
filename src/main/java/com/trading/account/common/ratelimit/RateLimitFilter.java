package com.trading.account.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.common.exception.CustomException;
import com.trading.account.common.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// 별도 API Gateway 서비스가 없는 모놀리식 구조라, 그 역할을 애플리케이션 최상단 필터가 대신한다.
// CorrelationIdFilter(HIGHEST_PRECEDENCE) 바로 다음, Spring Security 필터체인보다도 앞에 둬서
// 인증/인가 처리 전에 걸러내고 — 429로 막힌 요청도 X-Request-Id로 로그 추적은 가능하게 한다.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_URI = "/api/auth/login";

    private final TokenBucketRateLimiter generalRateLimiter;
    private final TokenBucketRateLimiter loginRateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(@Qualifier("generalRateLimiter") TokenBucketRateLimiter generalRateLimiter,
                            @Qualifier("loginRateLimiter") TokenBucketRateLimiter loginRateLimiter,
                            ObjectMapper objectMapper) {
        this.generalRateLimiter = generalRateLimiter;
        this.loginRateLimiter = loginRateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientKey = resolveClientKey(request);
        TokenBucketRateLimiter rateLimiter = isLoginRequest(request) ? loginRateLimiter : generalRateLimiter;
        try {
            if (!rateLimiter.tryConsume(clientKey)) {
                writeError(response, ErrorCode.TOO_MANY_REQUESTS, rateLimiter.getRetryAfterSeconds());
                return;
            }
        } catch (CustomException e) {
            if (e.getErrorCode() != ErrorCode.RATE_LIMIT_UNAVAILABLE) throw e;
            writeError(response, ErrorCode.RATE_LIMIT_UNAVAILABLE, 1);
            return;
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Probe/metrics availability must not depend on the request limiter. SecurityConfig
        // still controls access to Actuator details and to the dedicated metrics port.
        String path = request.getRequestURI();
        return HttpMethod.GET.matches(request.getMethod())
                && (path.equals("/livez") || path.equals("/readyz")
                || path.equals("/actuator/health") || path.equals("/actuator/prometheus"));
    }

    // Never parse forwarded headers here. In prod, Tomcat RemoteIpValve rewrites remoteAddr only when the
    // TCP peer is a trusted proxy (application-prod.yaml); elsewhere headers are ignored (strategy=none).
    String resolveClientKey(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private void writeError(HttpServletResponse response, ErrorCode error, int retryAfterSeconds) throws IOException {
        response.setStatus(error.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfterSeconds)));
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(error)));
    }

    // 로그인은 일반 API보다 훨씬 좁은 한도가 필요하다 (브루트포스 방지) — 하나의 버킷을
    // 공유하면 그 좁은 한도에 맞춰 일반 거래 트래픽까지 같이 막히므로 버킷 자체를 분리한다.
    private boolean isLoginRequest(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod()) && LOGIN_URI.equals(request.getRequestURI());
    }
}
