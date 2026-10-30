package com.cellforge.edge.gateway.adapter.kafka;

import com.cellforge.edge.gateway.application.UsageEvents;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
class KafkaUsageEvents implements UsageEvents {

    static final String TOPIC = "cellforge.usage";
    private static final Logger log = LoggerFactory.getLogger(KafkaUsageEvents.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    KafkaUsageEvents(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    @Override
    public void publish(ApiCall call) {
        try {
            // Keyed by tenant so one tenant's events stay ordered on one partition.
            kafka.send(TOPIC, call.tenantId().toString(), json.writeValueAsString(call))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        log.warn("Usage event {} not published: {}", call.eventId(), error.getMessage());
                    }
                });
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Usage event {} not published: {}", call.eventId(), e.getMessage());
        }
    }
}
