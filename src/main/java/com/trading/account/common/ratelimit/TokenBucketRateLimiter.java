package com.trading.account.common.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;

// Redis 하나로 여러 애플리케이션 인스턴스(k3s 파드)가 같은 버킷 상태를 공유해야 rate limit이
// 실제로 의미가 있다 (인스턴스별 로컬 카운터는 파드 수만큼 한도가 늘어나버림).
// GET-then-SET이 아니라 Lua 스크립트 하나로 조회+갱신을 원자적으로 묶어야 동시 요청 사이에서
// 토큰을 이중으로 소비하는 레이스 컨디션을 막을 수 있다 (Redis는 스크립트 실행 중 다른 명령을 끼워넣지 않음).
//
// 용도별로(일반 API / 로그인) 인스턴스를 따로 만들어 쓴다 — 로그인은 브루트포스 방지를 위해
// 훨씬 좁은 한도가 필요한데, 일반 API와 버킷을 공유하면 그 한도에 맞춰 전체를 좁혀야 해서
// 정상적인 거래 트래픽까지 막힌다. keyPrefix를 다르게 둬서 Redis 상의 버킷도 서로 분리한다.
@Slf4j
public class TokenBucketRateLimiter {

    private static final String SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refillTokens = tonumber(ARGV[2])
            local refillPeriodSeconds = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])

            local bucket = redis.call('HMGET', key, 'tokens', 'timestamp')
            local tokens = tonumber(bucket[1])
            local timestamp = tonumber(bucket[2])

            if tokens == nil then
                tokens = capacity
                timestamp = now
            end

            local elapsed = math.max(0, now - timestamp)
            tokens = math.min(capacity, tokens + (elapsed / refillPeriodSeconds) * refillTokens)

            local allowed = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end

            redis.call('HSET', key, 'tokens', tostring(tokens), 'timestamp', tostring(now))
            redis.call('EXPIRE', key, refillPeriodSeconds * 2)

            return allowed
            """;

    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = RedisScript.of(SCRIPT, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final int capacity;
    private final int refillTokens;
    private final int refillPeriodSeconds;

    public TokenBucketRateLimiter(StringRedisTemplate redisTemplate, String keyPrefix, RateLimitProperties.Bucket bucket) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
        this.capacity = bucket.capacity();
        this.refillTokens = bucket.refillTokens();
        this.refillPeriodSeconds = bucket.refillPeriodSeconds();
    }

    public boolean tryConsume(String clientKey) {
        try {
            Long allowed = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(keyPrefix + clientKey),
                    String.valueOf(capacity),
                    String.valueOf(refillTokens),
                    String.valueOf(refillPeriodSeconds),
                    String.valueOf(Instant.now().toEpochMilli() / 1000.0));
            return allowed != null && allowed == 1L;
        } catch (Exception e) {
            // ponytail: Redis 장애 시 fail-open(허용) — rate limiter는 보호장치이지 핵심 비즈니스
            // 로직이 아니므로, Redis 하나가 죽었다고 전체 API를 막으면 가용성이 더 크게 깨진다.
            // Redis 이중화나 서킷브레이커(IS-26)로 장애 자체를 줄이는 건 후속 과제.
            log.warn("Rate limit check failed, allowing request (fail-open): {}", e.getMessage());
            return true;
        }
    }
}
