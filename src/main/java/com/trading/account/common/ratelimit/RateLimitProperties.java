package com.trading.account.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(Bucket general, Bucket login) {

    public RateLimitProperties {
        if (general == null || login == null) {
            throw new IllegalArgumentException("Both general and login rate limit buckets are required");
        }
    }

    public record Bucket(int capacity, int refillTokens, int refillPeriodSeconds) {
        public Bucket {
            if (capacity <= 0 || refillTokens <= 0 || refillPeriodSeconds <= 0) {
                throw new IllegalArgumentException("Rate limit capacity, refill tokens and period must be positive");
            }
        }
    }
}
