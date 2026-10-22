package com.campusdash.application.usecase;

import com.campusdash.domain.campaign.model.RushRequest;
import com.campusdash.domain.campaign.model.RushRequestStatus;
import com.campusdash.domain.campaign.ports.RushRequestRepository;
import com.campusdash.domain.campaign.ports.RushReservationPort;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.ports.GrabSlotPort;
import com.campusdash.shared.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Service
public class RushReservationRecoveryUseCase {
    private final RushRequestRepository requests;
    private final RushReservationPort reservations;
    private final RushOrderTransactionalStep transaction;
    private final ErrandRepository errands;
    private final GrabSlotPort grabSlots;
    private final ErrandCachePort errandCache;

    public RushReservationRecoveryUseCase(RushRequestRepository requests,
                                          RushReservationPort reservations,
                                          RushOrderTransactionalStep transaction,
                                          ErrandRepository errands,
                                          GrabSlotPort grabSlots,
                                          ErrandCachePort errandCache) {
        this.requests = requests;
        this.reservations = reservations;
        this.transaction = transaction;
        this.errands = errands;
        this.grabSlots = grabSlots;
        this.errandCache = errandCache;
    }

    public int recover(long timeoutSeconds, int limit) {
        int fixed = 0;
        Instant now = Instant.now();
        Instant before = now.minusSeconds(timeoutSeconds);
        Set<String> handled = new HashSet<>();

        // 已经进入MySQL、但长时间没有终态的请求。
        for (var request : requests.findStaleProcessing(before, limit)) {
            handled.add(request.requestId());
            if (reservations.rollback(request.campaignId(), request.userId(), request.requestId(),
                    RushRequestStatus.DEAD)) {
                requests.markFailure(request.requestId(), RushRequestStatus.DEAD,
                        ErrorCode.RUSH_RESERVATION_TIMEOUT.name());
                requests.findByRequestId(request.requestId()).ifPresent(reservations::cacheResult);
                fixed++;
            }
        }

        // Redis预占完成、但Worker在MySQL事务前宕机时，DB里没有任何行，只扫DB永远发现不了。
        for (var snapshot : reservations.findStaleProcessing(before, limit)) {
            if (!handled.add(snapshot.requestId())) continue;
            var db = requests.findByRequestId(snapshot.requestId());
            if (db.isPresent() && db.get().status() == RushRequestStatus.SUCCESS) {
                fixed += repairSuccess(db.get());
                continue;
            }
            if (db.isPresent() && db.get().terminal()) {
                if (db.get().status() != RushRequestStatus.SUCCESS) {
                    // 失败终态不等于Redis已经完成补偿。rollback脚本幂等，可安全重放。
                    reservations.rollback(db.get().campaignId(), db.get().userId(),
                            db.get().requestId(), db.get().status());
                }
                reservations.cacheResult(db.get());
                continue;
            }
            boolean canFinish = snapshot.status() == RushRequestStatus.QUEUED
                    || reservations.rollback(snapshot.campaignId(), snapshot.userId(),
                    snapshot.requestId(), RushRequestStatus.DEAD);
            if (!canFinish) continue;
            if (snapshot.hasMessageMetadata()) {
                transaction.persistFailure(snapshot.toMessage(), RushRequestStatus.DEAD,
                        ErrorCode.RUSH_RESERVATION_TIMEOUT);
            }
            RushRequest dead = requests.findByRequestId(snapshot.requestId())
                    .orElseGet(() -> new RushRequest(snapshot.requestId(), snapshot.campaignId(),
                            snapshot.campusId(), snapshot.userId(), RushRequestStatus.DEAD,
                            snapshot.title(), snapshot.rewardCents(), null,
                            ErrorCode.RUSH_RESERVATION_TIMEOUT.name(),
                            snapshot.acceptedAt(), now, now));
            reservations.cacheResult(dead);
            fixed++;
        }

        // DB提交后进程宕机时，补写结果缓存并重建活动Errand的抢单槽。
        for (var success : requests.findSuccessfulSince(now.minusSeconds(86_400), limit)) {
            fixed += repairSuccess(success);
        }
        return fixed;
    }

    private int repairSuccess(RushRequest success) {
        boolean resultMissing = reservations.findCached(success.requestId())
                .map(cached -> cached.status() != RushRequestStatus.SUCCESS
                        || !Objects.equals(success.errandId(), cached.errandId()))
                .orElse(true);
        if (resultMissing) reservations.cacheResult(success);

        boolean slotMissing = false;
        if (success.errandId() != null && grabSlots.remainingSlot(success.errandId()) < 0) {
            var errand = errands.findById(success.errandId());
            if (errand.isPresent()) {
                int remaining = Math.max(0, errand.get().slotTotal() - errand.get().slotTaken());
                grabSlots.initSlot(success.errandId(), remaining, 7 * 24 * 3600L);
                errandCache.registerExisting(success.errandId());
                slotMissing = true;
            }
        }
        return resultMissing || slotMissing ? 1 : 0;
    }
}
