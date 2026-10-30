package com.cellforge.cell.tenancy.application;

import com.cellforge.cell.tenancy.domain.Entitlements;
import java.util.UUID;

public interface EntitlementSource {

    Entitlements forTenant(UUID tenantId);

    void evict(UUID tenantId);
}
