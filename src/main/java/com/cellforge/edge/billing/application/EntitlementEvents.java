package com.cellforge.edge.billing.application;

import java.util.UUID;

/** Tells every cell and edge replica that a tenant's plan or standing changed, so cached copies go now. */
public interface EntitlementEvents {

    String TOPIC = "cellforge.entitlements";

    void changed(UUID tenantId);
}
