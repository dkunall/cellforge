package com.cellforge.edge.directory.application;

import com.cellforge.edge.directory.domain.CellLoad;
import com.cellforge.edge.directory.domain.Placement;
import com.cellforge.edge.directory.domain.RouteStatus;
import com.cellforge.edge.directory.domain.TenantRoute;
import com.cellforge.shared.error.StateConflictException;
import com.cellforge.shared.error.UnavailableException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Creating a workspace is a small saga: claim the slug in the directory, provision it in the chosen cell, then
 * mark it active. Each step is safe to repeat, so a client that retries after a failure finishes the job
 * instead of getting a half-made tenant.
 */
@Service
public class TenantProvisioning {

    public record Result(TenantRoute tenant, boolean created) {
    }

    private final Directory directory;
    private final CellClient cells;
    private final TenantLifecycle lifecycle;

    public TenantProvisioning(Directory directory, CellClient cells, TenantLifecycle lifecycle) {
        this.directory = directory;
        this.cells = cells;
        this.lifecycle = lifecycle;
    }

    public Result create(String slug, String name, String plan, CellClient.Owner owner) {
        boolean created = false;
        TenantRoute route = directory.bySlug(slug).orElse(null);
        if (route == null) {
            CellLoad cell = Placement.choose(directory.loads())
                .orElseThrow(() -> new UnavailableException("no-cell", "No cell is accepting new tenants", 30));
            created = directory.insert(UUID.randomUUID(), slug, name, cell.cellId(), owner.userId());
            route = directory.bySlug(slug).orElseThrow();
        }
        if (!route.ownerUserId().equals(owner.userId())) {
            throw new StateConflictException("slug-taken", "The workspace name " + slug + " is taken");
        }
        if (route.status() == RouteStatus.PROVISIONING) {
            lifecycle.opened(route.id(), plan);
            cells.provision(route.cellUrl(), route.id(), route.slug(), route.name(), owner);
            directory.setStatus(route.id(), RouteStatus.ACTIVE);
            route = directory.byId(route.id()).orElseThrow();
        }
        return new Result(route, created);
    }
}
