package com.waller.wallet_platform.hold.job;

import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import com.waller.wallet_platform.hold.entity.HoldStatus;
import com.waller.wallet_platform.hold.repository.HoldRepository;
import com.waller.wallet_platform.hold.service.HoldService;

/**
 * Marks ACTIVE holds past expiresAt as EXPIRED, so HoldExpired is published without anyone having to ask.
 * The funds are already free before this runs: an expired hold no longer counts against the available balance
 * (HoldRepository.sumAmountByAccountIdAndStatus), so a slow or stopped job only delays the event.
 */
@Component
@Slf4j
public class HoldExpiryJob {

    private final HoldRepository holdRepository;
    private final HoldService holdService;
    private final int batchSize;

    public HoldExpiryJob(
            HoldRepository holdRepository,
            HoldService holdService,
            @Value("${hold.expiry.batch-size:100}") int batchSize) {
        this.holdRepository = holdRepository;
        this.holdService = holdService;
        this.batchSize = batchSize;
    }

    // Ids only, then one transaction per hold: a failure (e.g. the merchant captured it meanwhile) skips that hold
    @Scheduled(fixedDelayString = "${hold.expiry.poll-interval:PT30S}")
    @SchedulerLock(name = "holdExpiry", lockAtMostFor = "PT5M")
    public void expireDue() {
        List<Long> ids = holdRepository.findExpiredIds(
                HoldStatus.ACTIVE, Instant.now(), PageRequest.of(0, batchSize));
        int expired = 0;
        for (Long id : ids) {
            try {
                holdService.expireHold(id);
                expired++;
            } catch (RuntimeException e) {
                log.warn("Expiry of hold {} failed: {}", id, e.getMessage());
            }
        }
        if (expired > 0) {
            log.info("Expired {} holds", expired);
        }
    }
}
