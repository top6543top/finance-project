package com.trading.account.common.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@RequiredArgsConstructor
public class RateLimitConfig {

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;

    @Bean
    public TokenBucketRateLimiter generalRateLimiter() {
        return new TokenBucketRateLimiter(redisTemplate, "rate-limit:general:", properties.general());
    }

    @Bean
    public TokenBucketRateLimiter loginRateLimiter() {
        return new TokenBucketRateLimiter(redisTemplate, "rate-limit:login:", properties.login());
    }
}
