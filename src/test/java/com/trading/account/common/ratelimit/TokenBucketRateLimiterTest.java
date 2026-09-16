package com.trading.account.common.ratelimit;

import com.trading.account.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// refill-period-seconds를 넉넉히 길게 잡아, 테스트 실행 중 자연 리필로 결과가 흔들리지 않게 한다.
@SpringBootTest(properties = {
        "rate-limit.general.capacity=5",
        "rate-limit.general.refill-tokens=5",
        "rate-limit.general.refill-period-seconds=3600",
        "rate-limit.login.capacity=5",
        "rate-limit.login.refill-tokens=5",
        "rate-limit.login.refill-period-seconds=3600"
})
class TokenBucketRateLimiterTest extends AbstractIntegrationTest {

    @Autowired
    @Qualifier("generalRateLimiter")
    private TokenBucketRateLimiter rateLimiter;

    @Test
    void tryConsume_withinCapacity_allowsThenRejectsAfterExhausted() {
        String key = "sequential-test-client";

        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.tryConsume(key)).isTrue();
        }
        assertThat(rateLimiter.tryConsume(key)).isFalse();
    }

    // Lua 스크립트로 조회+차감을 원자적으로 묶었는지가 이 테스트의 핵심 — 스레드끼리 조회 결과를
    // 겹쳐 읽으면(레이스) capacity(5)보다 더 많은 요청이 통과해버린다.
    @Test
    void tryConsume_concurrentRequests_allowsExactlyCapacityAndNoMore() throws InterruptedException {
        String key = "concurrent-test-client";
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger allowedCount = new AtomicInteger();
        List<Throwable> unexpectedFailures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    if (rateLimiter.tryConsume(key)) {
                        allowedCount.incrementAndGet();
                    }
                } catch (Throwable e) {
                    unexpectedFailures.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(unexpectedFailures).isEmpty();
        assertThat(allowedCount.get()).isEqualTo(5);
    }
}
