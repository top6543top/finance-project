package com.trading.account.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(int capacity, int refillTokens, int refillPeriodSeconds) {
}
