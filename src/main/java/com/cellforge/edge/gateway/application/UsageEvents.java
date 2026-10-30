package com.cellforge.edge.gateway.application;

import java.time.Instant;
import java.util.UUID;

public interface UsageEvents {

    record ApiCall(UUID eventId, UUID tenantId, Instant at, String method, int status) {
    }

    /** Best effort: a lost event under-bills one call, which is preferable to failing the request. */
    void publish(ApiCall call);
}
