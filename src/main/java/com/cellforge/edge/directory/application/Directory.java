package com.cellforge.edge.directory.application;

import com.cellforge.edge.directory.domain.CellLoad;
import com.cellforge.edge.directory.domain.RouteStatus;
import com.cellforge.edge.directory.domain.TenantRoute;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Directory {

    void registerCell(String id, String internalUrl);

    List<CellLoad> loads();

    Optional<String> cellUrl(String cellId);

    /** False when the slug is already taken. */
    boolean insert(UUID id, String slug, String name, String cellId, String ownerUserId);

    Optional<TenantRoute> bySlug(String slug);

    Optional<TenantRoute> byId(UUID id);

    void setStatus(UUID id, RouteStatus status);

    /** ACTIVE to MOVING, atomically; false if the tenant is not ACTIVE (another move holds it, say). */
    boolean beginMove(UUID id);

    /** Points the tenant at its new cell and ends the move. */
    void moveTo(UUID id, String cellId);
}
