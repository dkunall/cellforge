package com.cellforge.edge.metering.adapter.schedule;

import com.cellforge.edge.metering.application.Metering;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class UsageHousekeeping {

    private static final Logger log = LoggerFactory.getLogger(UsageHousekeeping.class);

    private final Metering metering;

    UsageHousekeeping(Metering metering) {
        this.metering = metering;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    void forgetOldEvents() {
        int removed = metering.forgetOldEvents();
        if (removed > 0) {
            log.info("Removed {} usage event ids past the dedupe window", removed);
        }
    }
}
