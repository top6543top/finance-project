package com.trading.account.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${app.security.public-docs:false}")
    private boolean publicDocs;

    @Value("${management.server.port:9090}")
    private int managementPort;

    @Value("${server.port:8080}")
    private int applicationPort;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 회원가입(POST /api/members)은 계정이 생기기 전 호출이라 인증이 걸릴 수 없어 로그인과 함께 permitAll.
    // 그 외 계좌/거래 API는 인증 필요하며, 계좌 소유 검증은 서비스 계층에서 수행한다.
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handler -> handler.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/members").permitAll()
                            .requestMatchers(HttpMethod.GET, "/livez", "/readyz").permitAll()
                            // Scraping is anonymous only on a separate socket port, with network
                            // policy limiting that port to monitoring. Forwarded headers are ignored.
                            .requestMatchers(request -> managementPort > 0 && managementPort != applicationPort
                                    && request.getLocalPort() == managementPort
                                    && HttpMethod.GET.matches(request.getMethod())
                                    && "/actuator/prometheus".equals(request.getRequestURI())).permitAll();
                    if (publicDocs) {
                        auth.requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
