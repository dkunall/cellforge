package com.cellforge.cell.tenancy.application;

import com.cellforge.cell.tenancy.domain.HostedTenant;
import com.cellforge.cell.tenancy.domain.TenantStatus;
import java.util.Optional;

/** Runs inside the tenant's scope, so "current" is the only tenant it can see. */
public interface HostedTenants {

    Optional<HostedTenant> current();

    void insert(HostedTenant tenant);

    void setStatus(TenantStatus status);

    void delete();
}
