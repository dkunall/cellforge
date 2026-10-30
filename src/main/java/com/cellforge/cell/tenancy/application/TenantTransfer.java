package com.cellforge.cell.tenancy.application;

import com.cellforge.cell.tenancy.domain.HostedTenant;
import com.cellforge.cell.tenancy.domain.TenantStatus;
import com.cellforge.shared.error.NotFoundException;
import com.cellforge.shared.error.StateConflictException;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * This cell's half of moving a tenant. The edge drives the order (freeze, export, restore elsewhere, compare,
 * flip, clean up); each step here is safe to repeat so the edge can retry after a timeout.
 */
@Service
public class TenantTransfer {

    public record Snapshot(Map<String, String> tables, Map<String, TenantSnapshots.TableDigest> digest) {
    }

    private final HostedTenants tenants;
    private final TenantSnapshots snapshots;

    public TenantTransfer(HostedTenants tenants, TenantSnapshots snapshots) {
        this.tenants = tenants;
        this.snapshots = snapshots;
    }

    /** Waits for running writes (they share-lock the tenant row) and turns later ones away with 503. */
    @Transactional
    public void freeze() {
        TenantStatus status = hosted().status();
        if (status == TenantStatus.MOVED) {
            throw new StateConflictException("tenant-moved", "Tenant has already moved away");
        }
        tenants.setStatus(TenantStatus.FROZEN);
    }

    @Transactional
    public void unfreeze() {
        if (hosted().status() == TenantStatus.FROZEN) {
            tenants.setStatus(TenantStatus.ACTIVE);
        }
    }

    @Transactional(readOnly = true)
    public Snapshot export() {
        if (hosted().status() != TenantStatus.FROZEN) {
            throw new StateConflictException("tenant-not-frozen", "Freeze the tenant before exporting it");
        }
        return new Snapshot(snapshots.export(), snapshots.digest());
    }

    /** Restores frozen; returns the digest computed here so the edge can compare it with the source's. */
    @Transactional
    public Map<String, TenantSnapshots.TableDigest> restore(Map<String, String> tables) {
        if (tenants.current().isPresent()) {
            throw new StateConflictException("tenant-exists", "This cell already holds the tenant");
        }
        snapshots.restore(tables);
        return snapshots.digest();
    }

    @Transactional
    public void activate() {
        if (hosted().status() == TenantStatus.FROZEN) {
            tenants.setStatus(TenantStatus.ACTIVE);
        }
    }

    @Transactional
    public void markMoved() {
        tenants.setStatus(TenantStatus.MOVED);
    }

    /** Never removes a tenant that is serving traffic; only a frozen copy or one that has moved away. */
    @Transactional
    public void purge() {
        var current = tenants.current();
        if (current.isEmpty()) {
            return;
        }
        if (current.get().status() == TenantStatus.ACTIVE) {
            throw new StateConflictException("tenant-active", "Refusing to delete a tenant that is still active here");
        }
        tenants.delete();
    }

    private HostedTenant hosted() {
        return tenants.current().orElseThrow(() -> new NotFoundException("tenant-not-found", "Tenant is not in this cell"));
    }
}
