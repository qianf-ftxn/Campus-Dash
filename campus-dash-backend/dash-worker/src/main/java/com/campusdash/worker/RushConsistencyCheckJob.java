package com.campusdash.worker;

import com.campusdash.domain.campaign.ports.RushCampaignRepository;
import com.campusdash.domain.campaign.ports.RushReservationPort;
import com.campusdash.domain.recon.ports.ReconRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** MySQL是最终事实源；本任务只报告差异，不自动修改活动销量或资金流水。 */
@Component
@ConditionalOnProperty(name = "dash.rush.enabled", havingValue = "true", matchIfMissing = true)
public class RushConsistencyCheckJob {
    private static final Logger log = LoggerFactory.getLogger(RushConsistencyCheckJob.class);
    private final RushCampaignRepository campaigns;
    private final RushReservationPort reservations;
    private final ReconRepository recon;
    private final long campusId;

    public RushConsistencyCheckJob(RushCampaignRepository campaigns,
                                   RushReservationPort reservations,
                                   ReconRepository recon,
                                   @Value("${dash.rush.consistency-campus-id:1}") long campusId) {
        this.campaigns = campaigns;
        this.reservations = reservations;
        this.recon = recon;
        this.campusId = campusId;
    }

    @Scheduled(fixedDelayString = "${dash.rush.consistency-interval-ms:300000}")
    public void check() {
        var today = java.time.LocalDate.now();
        for (var diff : recon.findRushDiffs()) {
            recon.recordDiff(today, diff.checkType(), diff.subject(),
                    diff.expected(), diff.actual(), diff.detail());
            log.error("rush.consistency.diff check={} subject={} expected={} actual={}",
                    diff.checkType(), diff.subject(), diff.expected(), diff.actual());
        }
        for (var campaign : campaigns.findByCampus(campusId)) {
            long remaining = reservations.remainingStock(campaign.id());
            if (remaining < 0) continue; // 尚未初始化的活动不制造假告警
            long participants = reservations.participantCount(campaign.id());
            long actual = remaining + participants;
            if (actual == campaign.quotaTotal()) continue;
            recon.recordDiff(today, "RUSH_REDIS_QUOTA", String.valueOf(campaign.id()),
                    (long) campaign.quotaTotal(), actual,
                    "Redis remaining + participant hash数量应等于活动总名额");
            log.error("rush.consistency.diff check=RUSH_REDIS_QUOTA campaignId={} quota={} remaining={} participants={}",
                    campaign.id(), campaign.quotaTotal(), remaining, participants);
        }
    }
}
