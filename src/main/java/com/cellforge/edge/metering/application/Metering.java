package com.cellforge.edge.metering.application;

import com.cellforge.edge.metering.domain.UsageEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Metering {

    /** Longer than any redelivery we expect and than one billing period, so the dedupe window covers both. */
    static final Duration DEDUPE_WINDOW = Duration.ofDays(35);

    private final UsageLedger ledger;
    private final Clock clock;

    public Metering(UsageLedger ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    @Transactional
    public void record(UsageEvent event) {
        if (ledger.recordOnce(event)) {
            ledger.addToHour(event.tenantId(), event.hour(), 1, event.failed() ? 1 : 0);
        }
    }

    @Transactional(readOnly = true)
    public long calls(UUID tenantId, Instant from, Instant to) {
        return ledger.callsBetween(tenantId, from, to);
    }

    @Transactional
    public int forgetOldEvents() {
        return ledger.forgetEventsBefore(clock.instant().minus(DEDUPE_WINDOW));
    }
}
