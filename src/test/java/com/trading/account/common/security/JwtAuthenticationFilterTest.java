package com.trading.account.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {
    private static final String SECRET = "test-secret-at-least-thirty-two-bytes-for-jwt";
    private final JwtTokenProvider provider = new JwtTokenProvider(SECRET, 60000);
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test void genuineTokenAuthenticatesNumericMemberId() throws Exception {
        assertThat(principal(provider.generateToken(7L, "member@example.com"))).isEqualTo(7L);
    }

    @Test void invalidSubjectsRemainUnauthenticatedInsteadOfCrashing() throws Exception {
        for (String subject : new String[]{"not-a-number", "0", "-1", "9223372036854775808"}) {
            assertThat(principal(signedToken(subject, new Date(System.currentTimeMillis() + 60000)))).isNull();
        }
    }

    @Test void expiredMalformedAndTamperedTokensRemainUnauthenticated() throws Exception {
        String valid = provider.generateToken(7L, "member@example.com");
        String[] parts = valid.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        for (String token : new String[]{"broken", "", tampered, signedToken("7", new Date(1000))}) {
            assertThat(principal(token)).isNull();
        }
    }

    @Test void signedTokensWithoutExpiryOrSubjectAreRejected() throws Exception {
        assertThat(principal(signedToken("7", null))).isNull();
        assertThat(principal(signedToken(null, new Date(System.currentTimeMillis() + 60000)))).isNull();
    }

    private Object principal(String token) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        AtomicReference<Object> principal = new AtomicReference<>();
        new JwtAuthenticationFilter(provider).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null) principal.set(authentication.getPrincipal());
        });
        return principal.get();
    }

    private String signedToken(String subject, Date expiry) {
        return Jwts.builder().subject(subject).expiration(expiry)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
