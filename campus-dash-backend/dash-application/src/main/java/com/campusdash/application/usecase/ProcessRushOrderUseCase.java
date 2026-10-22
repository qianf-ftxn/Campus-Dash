package com.campusdash.application.usecase;

import com.campusdash.domain.campaign.model.*;
import com.campusdash.domain.campaign.ports.*;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.grab.ports.GrabSlotPort;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class ProcessRushOrderUseCase {
    private static final Logger log = LoggerFactory.getLogger(ProcessRushOrderUseCase.class);
    private static final long SLOT_TTL_SECONDS = 7 * 24 * 3600;

    private final RushCampaignRepository campaigns;
    private final RushRequestRepository requests;
    private final RushReservationPort reservations;
    private final RushOrderTransactionalStep transaction;
    private final GrabSlotPort grabSlots;
    private final ErrandCachePort errandCache;
    private final RushResultNotifier notifier;

    public ProcessRushOrderUseCase(RushCampaignRepository campaigns,
                                   RushRequestRepository requests,
                                   RushReservationPort reservations,
                                   RushOrderTransactionalStep transaction,
                                   GrabSlotPort grabSlots,
                                   ErrandCachePort errandCache,
                                   RushResultNotifier notifier) {
        this.campaigns = campaigns;
        this.requests = requests;
        this.reservations = reservations;
        this.transaction = transaction;
        this.grabSlots = grabSlots;
        this.errandCache = errandCache;
        this.notifier = notifier;
    }

    public Outcome process(RushOrderMessage message) {
        if (!message.supportedSchema()) {
            safeFailure(message, RushRequestStatus.DEAD, ErrorCode.RUSH_PROCESS_CONFLICT, false);
            return Outcome.DEAD;
        }
        RushCampaign campaign = campaigns.findById(message.campaignId()).orElse(null);
        if (campaign == null || campaign.campusId() != message.campusId()) {
            safeFailure(message, RushRequestStatus.FAILED_INVALID, ErrorCode.RUSH_CAMPAIGN_NOT_FOUND, false);
            return Outcome.COMPLETED;
        }
        RushCampaign.DisplayStatus acceptedStatus = campaign.displayStatus(message.acceptedAt());
        if (acceptedStatus != RushCampaign.DisplayStatus.ACTIVE) {
            ErrorCode code = switch (acceptedStatus) {
                case NOT_STARTED -> ErrorCode.RUSH_NOT_STARTED;
                case ENDED -> ErrorCode.RUSH_ENDED;
                case DISABLED -> ErrorCode.RUSH_DISABLED;
                case ACTIVE -> throw new IllegalStateException();
            };
            safeFailure(message, RushRequestStatus.FAILED_INVALID, code, false);
            return Outcome.COMPLETED;
        }

        var existing = requests.findByRequestId(message.requestId());
        if (existing.isPresent() && existing.get().terminal()) {
            repairTerminalReservation(existing.get());
            return Outcome.COMPLETED;
        }

        try {
            reservations.initialize(campaign.id(), campaign.quotaRemaining());
            RushReservationOutcome reserved = reservations.reserve(campaign.id(), message.userId(),
                    message.requestId(), Instant.now());
            switch (reserved) {
                case SOLD_OUT -> {
                    safeFailure(message, RushRequestStatus.FAILED_SOLD_OUT, ErrorCode.RUSH_SOLD_OUT, false);
                    return Outcome.COMPLETED;
                }
                case DUPLICATE_USER -> {
                    safeFailure(message, RushRequestStatus.FAILED_DUPLICATE, ErrorCode.RUSH_DUPLICATE_USER, false);
                    return Outcome.COMPLETED;
                }
                case DUPLICATE_REQUEST_TERMINAL -> {
                    requests.findByRequestId(message.requestId()).ifPresent(this::cacheAndNotify);
                    return Outcome.COMPLETED;
                }
                case CAMPAIGN_NOT_INITIALIZED -> {
                    return Outcome.RETRY;
                }
                case RESERVED, RESERVED_BY_SAME_REQUEST -> { }
            }

            long errandId = transaction.create(message);
            RushRequest success = requests.findByRequestId(message.requestId()).orElseThrow();
            // DB提交是成功事实；以下缓存动作失败时让MQ重试，重放会走终态修复分支。
            grabSlots.initSlot(errandId, 1, SLOT_TTL_SECONDS);
            errandCache.registerExisting(errandId);
            cacheAndNotify(success);
            log.info("rush.process.success requestId={} campaignId={} userId={} errandId={}",
                    message.requestId(), message.campaignId(), message.userId(), errandId);
            return Outcome.COMPLETED;
        } catch (BizException e) {
            if (e.code() == ErrorCode.INSUFFICIENT_BALANCE) {
                safeFailure(message, RushRequestStatus.FAILED_INSUFFICIENT_BALANCE,
                        ErrorCode.INSUFFICIENT_BALANCE, true);
                return Outcome.COMPLETED;
            }
            if (e.code() == ErrorCode.RUSH_SOLD_OUT) {
                safeFailure(message, RushRequestStatus.FAILED_SOLD_OUT, ErrorCode.RUSH_SOLD_OUT, true);
                return Outcome.COMPLETED;
            }
            if (e.code() == ErrorCode.RUSH_PROCESS_CONFLICT) {
                return Outcome.RETRY;
            }
            throw e;
        } catch (RuntimeException e) {
            // 唯一索引可能代表并发重放或同用户其他请求；重新读取事实后再决定。
            var same = requests.findByRequestId(message.requestId());
            if (same.isPresent() && same.get().terminal()) {
                repairTerminalReservation(same.get());
                return Outcome.COMPLETED;
            }
            var userRequest = requests.findByCampaignAndUser(message.campaignId(), message.userId());
            if (userRequest.isPresent() && !userRequest.get().requestId().equals(message.requestId())) {
                safeFailure(message, RushRequestStatus.FAILED_DUPLICATE, ErrorCode.RUSH_DUPLICATE_USER, true);
                return Outcome.COMPLETED;
            }
            log.warn("rush.process.retry requestId={} campaignId={} userId={}",
                    message.requestId(), message.campaignId(), message.userId(), e);
            return Outcome.RETRY;
        }
    }

    private void safeFailure(RushOrderMessage message, RushRequestStatus status,
                             ErrorCode code, boolean rollback) {
        transaction.persistFailure(message, status, code);
        if (rollback) {
            reservations.rollback(message.campaignId(), message.userId(), message.requestId(), status);
        }
        RushRequest result = requests.findByRequestId(message.requestId()).orElseGet(() -> synthetic(message, status, code));
        cacheAndNotify(result);
        log.info("rush.process.business_failed requestId={} campaignId={} userId={} code={}",
                message.requestId(), message.campaignId(), message.userId(), code);
    }

    private void cacheAndNotify(RushRequest request) {
        reservations.cacheResult(request);
        notifier.notifyResult(request);
    }

    /**
     * MySQL终态是业务事实，但失败终态并不证明Redis补偿已经完成。
     * MQ重放必须再次执行幂等补偿；Redis暂时不可用时让异常上抛，消息稍后重试。
     */
    private void repairTerminalReservation(RushRequest request) {
        if (request.status() != RushRequestStatus.SUCCESS) {
            reservations.rollback(request.campaignId(), request.userId(), request.requestId(), request.status());
        }
        cacheAndNotify(request);
    }

    private static RushRequest synthetic(RushOrderMessage m, RushRequestStatus status, ErrorCode code) {
        Instant now = Instant.now();
        return new RushRequest(m.requestId(), m.campaignId(), m.campusId(), m.userId(), status,
                m.title(), m.rewardCents(), null, code.name(), m.acceptedAt(), now, now);
    }

    public enum Outcome { COMPLETED, RETRY, DEAD }
}
