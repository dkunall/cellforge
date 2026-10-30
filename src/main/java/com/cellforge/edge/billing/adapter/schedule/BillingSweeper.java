package com.cellforge.edge.billing.adapter.schedule;

import com.cellforge.edge.billing.application.Billing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every edge replica runs this; locks and the one-invoice-per-period index make overlapping runs harmless. */
@Component
@ConditionalOnProperty(name = "cellforge.billing.sweep", havingValue = "true", matchIfMissing = true)
class BillingSweeper {

    private static final Logger log = LoggerFactory.getLogger(BillingSweeper.class);
    private final Billing billing;

    BillingSweeper(Billing billing) {
        this.billing = billing;
    }

    @Scheduled(fixedDelayString = "${cellforge.billing.sweep-interval:PT1M}", initialDelayString = "PT30S")
    void sweep() {
        Billing.RunResult result = billing.runDue();
        if (result.trialsEnded() + result.invoicesIssued() + result.suspended() > 0) {
            log.info("Billing sweep: {}", result);
        }
    }
}
