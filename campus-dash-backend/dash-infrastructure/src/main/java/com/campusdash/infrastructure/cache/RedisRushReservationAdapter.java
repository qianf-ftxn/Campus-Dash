package com.campusdash.infrastructure.cache;

import com.campusdash.domain.campaign.model.RushRequest;
import com.campusdash.domain.campaign.model.RushRequestStatus;
import com.campusdash.domain.campaign.model.RushOrderMessage;
import com.campusdash.domain.campaign.model.RushReservationOutcome;
import com.campusdash.domain.campaign.model.RushReservationSnapshot;
import com.campusdash.domain.campaign.ports.RushReservationPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;

@Component
public class RedisRushReservationAdapter implements RushReservationPort {
    private final StringRedisTemplate redis;
    private final RedisScript<Long> reserveScript;
    private final RedisScript<Long> rollbackScript;
    private final long ttlSeconds;

    public RedisRushReservationAdapter(StringRedisTemplate redis,
                                       @Qualifier("rushReserveScript") RedisScript<Long> reserveScript,
                                       @Qualifier("rushRollbackScript") RedisScript<Long> rollbackScript,
                                       @Value("${dash.rush.result-ttl-seconds:86400}") long ttlSeconds) {
        this.redis = redis;
        this.reserveScript = reserveScript;
        this.rollbackScript = rollbackScript;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public RushReservationOutcome reserve(long campaignId, long userId, String requestId, Instant now) {
        Long code = redis.execute(reserveScript,
                List.of(stockKey(campaignId), usersKey(campaignId), requestKey(campaignId, requestId)),
                String.valueOf(userId), requestId, String.valueOf(now.toEpochMilli()), String.valueOf(ttlSeconds));
        if (code == null) throw new IllegalStateException("活动预占脚本无返回值");
        return RushReservationOutcome.fromCode(code);
    }

    @Override
    public boolean rollback(long campaignId, long userId, String requestId, RushRequestStatus failedStatus) {
        Long code = redis.execute(rollbackScript,
                List.of(stockKey(campaignId), usersKey(campaignId), requestKey(campaignId, requestId)),
                String.valueOf(userId), requestId, failedStatus.name(), String.valueOf(ttlSeconds));
        return code != null && (code == 1L || code == 2L);
    }

    @Override
    public void initialize(long campaignId, int quotaRemaining) {
        redis.opsForValue().setIfAbsent(stockKey(campaignId), String.valueOf(Math.max(0, quotaRemaining)));
    }

    @Override
    public void cacheQueued(RushOrderMessage message) {
        long campaignId = message.campaignId();
        String requestId = message.requestId();
        String key = requestKey(campaignId, requestId);
        // 消息发送后消费者可能立刻把状态推进到 RESERVED/SUCCESS。
        // QUEUED 只能在 status 不存在时写，绝不能把更晚的终态覆盖回处理中。
        redis.opsForHash().putIfAbsent(key, "status", RushRequestStatus.QUEUED.name());
        // 即使消费者已经把状态推进到RESERVED，也要补全恢复所需的消息字段，
        // 但所有字段都用HSETNX，绝不把消费者写入的新状态覆盖回QUEUED。
        putIfAbsent(key, "campaignId", campaignId);
        putIfAbsent(key, "campusId", message.campusId());
        putIfAbsent(key, "userId", message.userId());
        redis.opsForHash().putIfAbsent(key, "title", message.title());
        putIfAbsent(key, "rewardCents", message.rewardCents());
        putIfAbsent(key, "acceptedAt", message.acceptedAt().toEpochMilli());
        redis.expire(key, Duration.ofSeconds(ttlSeconds));
        redis.opsForValue().set(indexKey(requestId), String.valueOf(campaignId), Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public void cacheResult(RushRequest request) {
        String key = requestKey(request.campaignId(), request.requestId());
        Map<String, String> values = new HashMap<>();
        values.put("campaignId", String.valueOf(request.campaignId()));
        values.put("userId", String.valueOf(request.userId()));
        values.put("status", request.status().name());
        values.put("acceptedAt", String.valueOf(request.acceptedAt().toEpochMilli()));
        values.put("errandId", request.errandId() == null ? "" : String.valueOf(request.errandId()));
        values.put("errorCode", request.errorCode() == null ? "" : request.errorCode());
        redis.opsForHash().putAll(key, values);
        redis.expire(key, Duration.ofSeconds(ttlSeconds));
        redis.opsForValue().set(indexKey(request.requestId()), String.valueOf(request.campaignId()),
                Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public Optional<RushRequest> findCached(String requestId) {
        String campaignText = redis.opsForValue().get(indexKey(requestId));
        if (campaignText == null) return Optional.empty();
        long campaignId = Long.parseLong(campaignText);
        Map<Object, Object> values = redis.opsForHash().entries(requestKey(campaignId, requestId));
        if (values.isEmpty()) return Optional.empty();
        String status = text(values, "status");
        String errand = text(values, "errandId");
        String errorCode = text(values, "errorCode");
        String accepted = text(values, "acceptedAt");
        Instant acceptedAt = accepted.isBlank() ? Instant.EPOCH : Instant.ofEpochMilli(Long.parseLong(accepted));
        Instant now = Instant.now();
        return Optional.of(new RushRequest(requestId, campaignId,
                0L, Long.parseLong(text(values, "userId")), RushRequestStatus.valueOf(status),
                "", 1L, errand.isBlank() ? null : Long.parseLong(errand),
                errorCode.isBlank() ? null : errorCode, acceptedAt, now, now));
    }

    @Override
    public List<RushReservationSnapshot> findStaleProcessing(Instant before, int limit) {
        if (limit <= 0) return List.of();
        List<RushReservationSnapshot> result = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match("rush:*:request:*").count(Math.max(100, limit)).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext() && result.size() < limit) {
                String key = cursor.next();
                Map<Object, Object> values = redis.opsForHash().entries(key);
                String statusText = text(values, "status");
                if (!RushRequestStatus.QUEUED.name().equals(statusText)
                        && !RushRequestStatus.RESERVED.name().equals(statusText)) continue;
                String timeText = RushRequestStatus.RESERVED.name().equals(statusText)
                        ? text(values, "reservedAt") : text(values, "acceptedAt");
                if (timeText.isBlank() || Long.parseLong(timeText) >= before.toEpochMilli()) continue;
                RushReservationSnapshot snapshot = snapshot(key, values, statusText, timeText);
                if (snapshot != null) result.add(snapshot);
            }
        }
        return result;
    }

    @Override
    public long remainingStock(long campaignId) {
        String value = redis.opsForValue().get(stockKey(campaignId));
        return value == null ? -1L : Long.parseLong(value);
    }

    @Override
    public long participantCount(long campaignId) {
        Long size = redis.opsForHash().size(usersKey(campaignId));
        return size == null ? 0L : size;
    }

    private RushReservationSnapshot snapshot(String key, Map<Object, Object> values,
                                             String statusText, String processingAt) {
        try {
            int marker = key.indexOf(":request:");
            if (marker < 0) return null;
            String requestId = key.substring(marker + ":request:".length());
            String acceptedText = text(values, "acceptedAt");
            Instant acceptedAt = acceptedText.isBlank()
                    ? Instant.ofEpochMilli(Long.parseLong(processingAt))
                    : Instant.ofEpochMilli(Long.parseLong(acceptedText));
            Instant reservedAt = Instant.ofEpochMilli(Long.parseLong(processingAt));
            return new RushReservationSnapshot(requestId,
                    Long.parseLong(text(values, "campaignId")),
                    parseLong(values, "campusId", 0L),
                    Long.parseLong(text(values, "userId")),
                    RushRequestStatus.valueOf(statusText),
                    text(values, "title"),
                    parseLong(values, "rewardCents", 1L),
                    acceptedAt, reservedAt);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private void putIfAbsent(String key, String field, long value) {
        redis.opsForHash().putIfAbsent(key, field, String.valueOf(value));
    }

    private static long parseLong(Map<Object, Object> values, String key, long fallback) {
        String value = text(values, key);
        return value.isBlank() ? fallback : Long.parseLong(value);
    }

    private static String text(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }

    private static String stockKey(long campaignId) { return "rush:{" + campaignId + "}:stock"; }
    private static String usersKey(long campaignId) { return "rush:{" + campaignId + "}:users"; }
    private static String requestKey(long campaignId, String requestId) {
        return "rush:{" + campaignId + "}:request:" + requestId;
    }
    private static String indexKey(String requestId) { return "rush:request-index:" + requestId; }
}
