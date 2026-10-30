package com.cellforge.edge.billing.adapter.kafka;

import com.cellforge.edge.billing.application.Accounts;
import com.cellforge.edge.billing.application.EntitlementEvents;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Best effort by design: if the event is lost, caches still expire on their TTL (30s here, 60s in cells),
 * so a missed event delays a plan change rather than losing it.
 */
@Component
class KafkaEntitlementEvents implements EntitlementEvents {

    private static final Logger log = LoggerFactory.getLogger(KafkaEntitlementEvents.class);

    private final KafkaTemplate<String, String> kafka;
    private final Accounts accounts;

    KafkaEntitlementEvents(KafkaTemplate<String, String> kafka, @Lazy Accounts accounts) {
        this.kafka = kafka;
        this.accounts = accounts;
    }

    @Override
    public void changed(UUID tenantId) {
        try {
            kafka.send(TOPIC, tenantId.toString(), tenantId.toString()).whenComplete((result, error) -> {
                if (error != null) {
                    log.warn("Entitlement change for {} not published: {}", tenantId, error.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Entitlement change for {} not published: {}", tenantId, e.getMessage());
        }
    }

    // A fresh group per process: every replica needs every event, and only events from now on matter.
    @KafkaListener(topics = TOPIC, groupId = "cellforge-edge-${random.uuid}",
        properties = {"auto.offset.reset=latest", "enable.auto.commit=true"})
    void onChange(String tenantId) {
        accounts.forget(UUID.fromString(tenantId));
    }
}
