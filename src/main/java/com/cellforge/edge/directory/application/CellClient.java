package com.cellforge.edge.directory.application;

import java.util.Optional;
import java.util.UUID;

/** The edge's calls into a cell's internal API. */
public interface CellClient {

    record Owner(String userId, String email, String displayName) {
    }

    void provision(String cellUrl, UUID tenantId, String slug, String name, Owner owner);

    long seats(String cellUrl, UUID tenantId);

    /** Empty when the user is not a member of the tenant. */
    Optional<String> role(String cellUrl, UUID tenantId, String userId);
}
