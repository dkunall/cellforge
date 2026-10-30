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

    void moveTo(UUID id, String cellId);
}
