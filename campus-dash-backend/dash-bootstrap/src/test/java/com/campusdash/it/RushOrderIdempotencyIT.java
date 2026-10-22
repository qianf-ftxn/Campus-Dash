package com.campusdash.it;

import com.campusdash.domain.campaign.model.*;
import com.campusdash.domain.campaign.ports.RushReservationPort;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"dash.mq.enabled=false"})
class RushOrderIdempotencyIT {
    private static final long CAMPAIGN = 990_901L;
    private static final String REQUEST = "rush:990901:shared-request";

    @Autowired RushReservationPort reservations;
    @Autowired StringRedisTemplate redis;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    @AfterEach
    void cleanup() {
        redis.delete("rush:{" + CAMPAIGN + "}:stock");
        redis.delete("rush:{" + CAMPAIGN + "}:users");
        redis.delete("rush:{" + CAMPAIGN + "}:request:" + REQUEST);
        redis.delete("rush:request-index:" + REQUEST);
    }

    @Test
    void queued_request_id_cannot_be_taken_over_by_another_user() {
        reservations.initialize(CAMPAIGN, 1);
        reservations.cacheQueued(message(1001));

        assertEquals(RushReservationOutcome.DUPLICATE_REQUEST_TERMINAL,
                reservations.reserve(CAMPAIGN, 2001, REQUEST, Instant.now()));
        assertEquals(1, reservations.remainingStock(CAMPAIGN));
        assertEquals(RushReservationOutcome.RESERVED,
                reservations.reserve(CAMPAIGN, 1001, REQUEST, Instant.now()));
    }

    @Test
    void compensation_is_idempotent_and_never_increases_stock_twice() {
        reservations.initialize(CAMPAIGN, 1);
        reservations.cacheQueued(message(1001));
        assertEquals(RushReservationOutcome.RESERVED,
                reservations.reserve(CAMPAIGN, 1001, REQUEST, Instant.now()));
        assertEquals(0, reservations.remainingStock(CAMPAIGN));

        assertTrue(reservations.rollback(CAMPAIGN, 1001, REQUEST,
                RushRequestStatus.FAILED_INSUFFICIENT_BALANCE));
        assertEquals(1, reservations.remainingStock(CAMPAIGN));
        assertTrue(reservations.rollback(CAMPAIGN, 1001, REQUEST,
                RushRequestStatus.FAILED_INSUFFICIENT_BALANCE));
        assertEquals(1, reservations.remainingStock(CAMPAIGN));
    }

    private static RushOrderMessage message(long userId) {
        return new RushOrderMessage(1, REQUEST, CAMPAIGN, 1, userId,
                Instant.now(), "午餐", 1500);
    }
}
