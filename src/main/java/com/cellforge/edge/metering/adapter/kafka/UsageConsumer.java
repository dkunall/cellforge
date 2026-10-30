package com.cellforge.edge.metering.adapter.kafka;

import com.cellforge.edge.metering.application.Metering;
import com.cellforge.edge.metering.domain.UsageEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Offsets are committed after the batch is handled, so a crash replays events; {@link Metering} drops the
 * repeats by event id.
 */
@Component
class UsageConsumer {

    private static final Logger log = LoggerFactory.getLogger(UsageConsumer.class);

    private final Metering metering;
    private final ObjectMapper json;

    UsageConsumer(Metering metering, ObjectMapper json) {
        this.metering = metering;
        this.json = json;
    }

    @KafkaListener(topics = "cellforge.usage")
    void onEvent(String payload) {
        UsageEvent event;
        try {
            event = json.readValue(payload, UsageEvent.class);
        } catch (JsonProcessingException e) {
            // A poison message would otherwise be retried forever and stall every tenant behind it.
            log.warn("Skipping unreadable usage event: {}", e.getOriginalMessage());
            return;
        }
        metering.record(event);
    }
}
