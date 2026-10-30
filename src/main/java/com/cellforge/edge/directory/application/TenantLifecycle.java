package com.cellforge.edge.directory.application;

import java.util.UUID;

/**
 * Hook for the modules that keep per-tenant state at the edge (billing). Must be idempotent: provisioning
 * calls it again on every retry until the tenant is active.
 */
public interface TenantLifecycle {

    void opened(UUID tenantId, String plan);
}
