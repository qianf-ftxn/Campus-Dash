package com.campusdash.application.usecase;

import com.campusdash.domain.campaign.model.RushCampaign;
import com.campusdash.domain.campaign.model.RushOrderMessage;
import com.campusdash.domain.campaign.model.RushRequest;
import com.campusdash.domain.campaign.ports.RushCampaignRepository;
import com.campusdash.domain.campaign.ports.RushOrderQueuePort;
import com.campusdash.domain.campaign.ports.RushRequestRepository;
import com.campusdash.domain.campaign.ports.RushReservationPort;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class SubmitRushOrderUseCase {
    private static final Logger log = LoggerFactory.getLogger(SubmitRushOrderUseCase.class);
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_:-]{8,64}");
    private static final long MAX_REWARD_CENTS = 10_000_000L;

    private final RushCampaignRepository campaigns;
    private final RushRequestRepository requests;
    private final RushReservationPort reservations;
    private final RushOrderQueuePort queue;
    private final Clock clock;

    @Autowired
    public SubmitRushOrderUseCase(RushCampaignRepository campaigns,
                                  RushRequestRepository requests,
                                  RushReservationPort reservations,
                                  RushOrderQueuePort queue) {
        this(campaigns, requests, reservations, queue, Clock.systemUTC());
    }

    SubmitRushOrderUseCase(RushCampaignRepository campaigns,
                           RushRequestRepository requests,
                           RushReservationPort reservations,
                           RushOrderQueuePort queue, Clock clock) {
        this.campaigns = campaigns;
        this.requests = requests;
        this.reservations = reservations;
        this.queue = queue;
        this.clock = clock;
    }

    public Accepted submit(Command cmd) {
        validate(cmd);
        RushCampaign campaign = campaigns.findById(cmd.campaignId())
                .orElseThrow(() -> new BizException(ErrorCode.RUSH_CAMPAIGN_NOT_FOUND));
        Instant now = clock.instant();
        switch (campaign.displayStatus(now)) {
            case DISABLED -> throw new BizException(ErrorCode.RUSH_DISABLED);
            case NOT_STARTED -> throw new BizException(ErrorCode.RUSH_NOT_STARTED);
            case ENDED -> throw new BizException(ErrorCode.RUSH_ENDED);
            case ACTIVE -> { }
        }

        Optional<RushRequest> existing = requests.findByRequestId(cmd.requestId())
                .or(() -> reservations.findCached(cmd.requestId()));
        if (existing.isPresent()) {
            RushRequest request = existing.get();
            if (request.userId() != cmd.userId()) throw new BizException(ErrorCode.RUSH_REQUEST_FORBIDDEN);
            return new Accepted(request.requestId(), request.status().publicStatus(),
                    request.errandId(), request.errorCode());
        }

        RushOrderMessage message = new RushOrderMessage(RushOrderMessage.CURRENT_SCHEMA_VERSION,
                cmd.requestId(), campaign.id(), campaign.campusId(), cmd.userId(), now,
                cmd.title().trim(), cmd.rewardCents());
        try {
            queue.enqueue(message);
        } catch (RuntimeException e) {
            log.warn("rush.queue.send.failed requestId={} campaignId={} userId={}",
                    cmd.requestId(), cmd.campaignId(), cmd.userId(), e);
            throw new BizException(ErrorCode.RUSH_QUEUE_UNAVAILABLE);
        }
        try {
            reservations.cacheQueued(message);
        } catch (RuntimeException e) {
            // Broker确认是受理事实，结果缓存只是读体验，不能反转已成功的发送。
            log.warn("活动QUEUED缓存写入失败 requestId={} campaignId={}", cmd.requestId(), campaign.id(), e);
        }
        log.info("rush.submit.accepted requestId={} campaignId={} userId={}",
                cmd.requestId(), campaign.id(), cmd.userId());
        return new Accepted(cmd.requestId(), "PROCESSING", null, null);
    }

    private static void validate(Command cmd) {
        if (cmd.requestId() == null || !REQUEST_ID.matcher(cmd.requestId()).matches()) {
            throw new BizException(ErrorCode.INVALID_REQUEST_ID);
        }
        if (cmd.title() == null || cmd.title().trim().isEmpty() || cmd.title().trim().length() > 64) {
            throw new BizException(ErrorCode.RUSH_PROCESS_CONFLICT, "title长度必须为1~64");
        }
        if (cmd.rewardCents() <= 0 || cmd.rewardCents() > MAX_REWARD_CENTS) {
            throw new BizException(ErrorCode.RUSH_PROCESS_CONFLICT, "rewardCents不合法");
        }
    }

    public record Command(long campaignId, long userId, String requestId, String title, long rewardCents) {}
    public record Accepted(String requestId, String status, Long errandId, String errorCode) {}
}
