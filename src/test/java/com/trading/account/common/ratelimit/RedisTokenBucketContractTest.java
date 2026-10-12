package com.trading.account.common.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// Real Redis Lua coverage; no unrelated MySQL/application context is needed here.
@Testcontainers
class RedisTokenBucketContractTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll static void disconnect() { if (connectionFactory != null) connectionFactory.destroy(); }

    @Test void sharedConcurrentBudgetAllowsExactlyCapacityAcrossInstances() throws Exception {
        String prefix = "rate-test:" + UUID.randomUUID() + ":";
        var configuration = new RateLimitProperties.Bucket(5, 1, 3600);
        TokenBucketRateLimiter first = new TokenBucketRateLimiter(redis, prefix, configuration);
        TokenBucketRateLimiter second = new TokenBucketRateLimiter(redis, prefix, configuration);
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        AtomicInteger allowed = new AtomicInteger();
        try {
            for (int i = 0; i < 50; i++) {
                TokenBucketRateLimiter instance = i % 2 == 0 ? first : second;
                tasks.add(executor.submit(() -> {
                    start.await();
                    if (instance.tryConsume("same-client")) allowed.incrementAndGet();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
            assertThat(allowed).hasValue(5);
            assertThat(first.tryConsume("same-client")).isFalse();
            assertThat(second.tryConsume("different-client")).isTrue();
        } finally { executor.shutdownNow(); }
    }

    @Test void depletedBucketCannotExpireBeforeItWouldNaturallyRefill() {
        String prefix = "ttl-test:" + UUID.randomUUID() + ":";
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(redis, prefix, new RateLimitProperties.Bucket(10, 1, 10));
        for (int i = 0; i < 10; i++) assertThat(limiter.tryConsume("client")).isTrue();
        assertThat(limiter.tryConsume("client")).isFalse();
        // Ten tokens at one token per ten seconds take 100 seconds to refill, not 20.
        assertThat(redis.getExpire(prefix + "client")).isGreaterThanOrEqualTo(99);
    }

    @Test void elapsedTimeRefillsOnlyTheEarnedTokens() {
        String prefix = "refill-test:" + UUID.randomUUID() + ":";
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(redis, prefix, new RateLimitProperties.Bucket(10, 1, 10));
        limiter.tryConsume("client");
        double timestamp = Double.parseDouble((String) redis.opsForHash().get(prefix + "client", "timestamp"));
        redis.opsForHash().put(prefix + "client", "tokens", "0");
        redis.opsForHash().put(prefix + "client", "timestamp", String.valueOf(timestamp - 20));
        assertThat(limiter.tryConsume("client")).isTrue();
        assertThat(limiter.tryConsume("client")).isTrue();
        assertThat(limiter.tryConsume("client")).isFalse();
    }
}
