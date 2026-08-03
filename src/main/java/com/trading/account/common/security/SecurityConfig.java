package com.trading.account.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 회원가입(POST /api/members)은 계정이 생기기 전 호출이라 인증이 걸릴 수 없어 로그인과 함께 permitAll.
    // 그 외 계좌/거래 API는 전부 인증 필요 — "누구 계좌인지" 검증(인가)은 IS-17에서 서비스 계층에 추가 예정.
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handler -> handler.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/members").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // 헬스체크(LB/k8s probe)는 JWT를 못 실어 보내므로 permitAll.
                        // Prometheus는 여기서 뺌 — 로그인 성공/실패 카운터 등 민감 지표가 있어 인증 필요.
                        // 지금(EC2) 배포엔 이걸 긁는 Prometheus가 없어 인증으로 막고,
                        // 나중에 k8s로 옮겨 management.server.port를 분리하면 이 규칙 자체를 안 거치게 되고
                        // NetworkPolicy 같은 네트워크 경계가 실제 보호를 담당하게 됨.
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
