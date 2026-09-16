package com.trading.account.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.common.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// 별도 API Gateway 서비스가 없는 모놀리식 구조라, 그 역할을 애플리케이션 최상단 필터가 대신한다.
// CorrelationIdFilter(HIGHEST_PRECEDENCE) 바로 다음, Spring Security 필터체인보다도 앞에 둬서
// 인증/인가 처리 전에 걸러내고 — 429로 막힌 요청도 X-Request-Id로 로그 추적은 가능하게 한다.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final TokenBucketRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientKey = resolveClientKey(request);
        if (!rateLimiter.tryConsume(clientKey)) {
            response.setStatus(ErrorCode.TOO_MANY_REQUESTS.getStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(ErrorCode.TOO_MANY_REQUESTS)));
            return;
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // readinessProbe/livenessProbe(IS-34)가 같은 파드 IP에서 짧은 주기로 반복 호출되므로
        // rate limit에 걸리면 파드가 오탐으로 재시작될 수 있어 헬스체크/메트릭은 대상에서 제외.
        return request.getRequestURI().startsWith("/actuator");
    }

    // 로드밸런서/인그레스 뒤에서는 client socket이 아니라 X-Forwarded-For의 원발신 IP로 제한해야
    // 실제 클라이언트별 제한이 된다. 헤더는 클라이언트가 위조할 수 있어 인증(JWT) 기반 제한이 더
    // 정확하지만, 로그인 API 자체도 보호 대상이라 인증 여부와 무관하게 걸리는 IP 기반을 기본으로 둔다.
    String resolveClientKey(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
