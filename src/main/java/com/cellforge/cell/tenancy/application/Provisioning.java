package com.cellforge.cell.tenancy.application;

import com.cellforge.cell.access.application.Members;
import com.cellforge.cell.access.domain.Member;
import com.cellforge.cell.access.domain.Role;
import com.cellforge.cell.tenancy.domain.HostedTenant;
import com.cellforge.cell.tenancy.domain.TenantStatus;
import com.cellforge.shared.error.StateConflictException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a tenant's home in this cell. The edge retries until it hears back, so a repeat with the same id and
 * slug is a no-op rather than an error.
 */
@Service
public class Provisioning {

    public record Owner(String userId, String email, String displayName) {
    }

    private final HostedTenants tenants;
    private final Members members;
    private final Clock clock;

    public Provisioning(HostedTenants tenants, Members members, Clock clock) {
        this.tenants = tenants;
        this.members = members;
        this.clock = clock;
    }

    /** Returns true if this call created the tenant. Runs in the tenant's internal scope. */
    @Transactional
    public boolean provision(String slug, String name, Owner owner) {
        UUID id = TenantContext.tenantId();
        Optional<HostedTenant> existing = tenants.current();
        if (existing.isPresent()) {
            if (!existing.get().slug().equals(slug)) {
                throw new StateConflictException("tenant-id-taken", "Tenant " + id + " exists here with another slug");
            }
            return false;
        }
        var now = clock.instant();
        tenants.insert(new HostedTenant(id, slug, name, TenantStatus.ACTIVE, now));
        members.insert(new Member(id, owner.userId(), owner.email(), owner.displayName(), Role.OWNER, now));
        return true;
    }
}
