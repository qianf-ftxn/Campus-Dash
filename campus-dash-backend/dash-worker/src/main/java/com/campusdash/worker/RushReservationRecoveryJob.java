package com.campusdash.worker;

import com.campusdash.application.usecase.RushReservationRecoveryUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dash.rush.enabled", havingValue = "true", matchIfMissing = true)
public class RushReservationRecoveryJob {
    private static final Logger log = LoggerFactory.getLogger(RushReservationRecoveryJob.class);
    private final RushReservationRecoveryUseCase recovery;
    private final long timeoutSeconds;

    public RushReservationRecoveryJob(RushReservationRecoveryUseCase recovery,
                                      @Value("${dash.rush.reserved-timeout-seconds:120}") long timeoutSeconds) {
        this.recovery = recovery;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${dash.rush.recovery-interval-ms:30000}")
    public void run() {
        int fixed = recovery.recover(timeoutSeconds, 200);
        if (fixed > 0) log.warn("rush.recovery.fixed count={}", fixed);
    }
}
