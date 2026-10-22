package com.campusdash.it;

import com.campusdash.application.usecase.RushReservationRecoveryUseCase;
import com.campusdash.domain.campaign.model.*;
import com.campusdash.domain.campaign.ports.RushReservationPort;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"dash.mq.enabled=false"})
class RushRecoveryIT {
    private static final long CAMPAIGN = 990_903L;
    private static final long USER = 990_903L;
    private static final String REQUEST = "rush:990903:recovery-request";

    @Autowired RushReservationPort reservations;
    @Autowired RushReservationRecoveryUseCase recovery;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM rush_request WHERE request_id=?", REQUEST);
        redis.delete("rush:{" + CAMPAIGN + "}:stock");
        redis.delete("rush:{" + CAMPAIGN + "}:users");
        redis.delete("rush:{" + CAMPAIGN + "}:request:" + REQUEST);
        redis.delete("rush:request-index:" + REQUEST);
    }

    @Test
    void failed_db_terminal_with_stale_redis_reservation_is_compensated() {
        Instant old = Instant.now().minusSeconds(300);
        reservations.initialize(CAMPAIGN, 1);
        assertEquals(RushReservationOutcome.RESERVED,
                reservations.reserve(CAMPAIGN, USER, REQUEST, old));
        jdbc.update("""
                INSERT INTO rush_request
                  (request_id,campaign_id,campus_id,user_id,status,title,reward_amount,error_code,accepted_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, REQUEST, CAMPAIGN, 1, USER, RushRequestStatus.FAILED_INSUFFICIENT_BALANCE.name(),
                "午餐", 1500, "INSUFFICIENT_BALANCE", Timestamp.from(old));

        recovery.recover(120, 200);

        assertEquals(1, reservations.remainingStock(CAMPAIGN));
        assertEquals(0, reservations.participantCount(CAMPAIGN));
        RushRequest cached = reservations.findCached(REQUEST).orElseThrow();
        assertEquals(RushRequestStatus.FAILED_INSUFFICIENT_BALANCE, cached.status());
    }
}
