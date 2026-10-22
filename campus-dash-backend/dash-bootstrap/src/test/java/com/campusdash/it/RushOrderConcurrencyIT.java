package com.campusdash.it;

import com.campusdash.domain.campaign.model.RushRequestStatus;
import com.campusdash.domain.campaign.model.RushReservationOutcome;
import com.campusdash.domain.campaign.ports.RushReservationPort;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Redis名额裁决的3000抢500并发回归；完整资金/Errand链路由RushLoadClient验收。 */
@SpringBootTest(properties = {"dash.mq.enabled=false"})
class RushOrderConcurrencyIT {
    private static final long CAMPAIGN = 990_904L;

    @Autowired RushReservationPort reservations;
    @Autowired StringRedisTemplate redis;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    @AfterEach
    void cleanup() {
        var keys = redis.keys("rush:{" + CAMPAIGN + "}:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void three_thousand_users_can_reserve_exactly_five_hundred_slots() throws Exception {
        int requests = 3000;
        int quota = 500;
        reservations.initialize(CAMPAIGN, quota);
        ExecutorService pool = Executors.newFixedThreadPool(128);
        CountDownLatch ready = new CountDownLatch(128);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger();
        try {
            List<Future<RushReservationOutcome>> futures = new ArrayList<>(requests);
            for (int i = 0; i < requests; i++) {
                final int user = i;
                futures.add(pool.submit(() -> {
                    if (sequence.getAndIncrement() < 128) ready.countDown();
                    start.await();
                    return reservations.reserve(CAMPAIGN, 100_000L + user,
                            "rush:990904:" + user, Instant.now());
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int success = 0;
            int soldOut = 0;
            for (Future<RushReservationOutcome> future : futures) {
                RushReservationOutcome outcome = future.get(20, TimeUnit.SECONDS);
                if (outcome == RushReservationOutcome.RESERVED) success++;
                if (outcome == RushReservationOutcome.SOLD_OUT) soldOut++;
            }

            assertEquals(quota, success);
            assertEquals(requests - quota, soldOut);
            assertEquals(0, reservations.remainingStock(CAMPAIGN));
            assertEquals(quota, reservations.participantCount(CAMPAIGN));
        } finally {
            pool.shutdownNow();
        }
    }
}
