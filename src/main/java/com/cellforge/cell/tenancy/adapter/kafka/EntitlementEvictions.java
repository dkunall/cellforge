package com.cellforge.cell.tenancy.adapter.kafka;

import com.cellforge.cell.tenancy.application.EntitlementSource;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Drops a tenant's cached plan when the edge says it changed, instead of waiting out the cache TTL. */
@Component
class EntitlementEvictions {

    private final EntitlementSource entitlements;

    EntitlementEvictions(EntitlementSource entitlements) {
        this.entitlements = entitlements;
    }

    // A fresh group per process so every cell replica sees every event.
    @KafkaListener(topics = "cellforge.entitlements", groupId = "cellforge-cell-${random.uuid}",
        properties = {"auto.offset.reset=latest", "enable.auto.commit=true"})
    void onChange(String tenantId) {
        entitlements.evict(UUID.fromString(tenantId));
    }
}
