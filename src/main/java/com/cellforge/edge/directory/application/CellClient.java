package com.cellforge.edge.directory.application;

import java.util.UUID;

/** The edge's calls into a cell's internal API. */
public interface CellClient {

    record Owner(String userId, String email, String displayName) {
    }

    void provision(String cellUrl, UUID tenantId, String slug, String name, Owner owner);
}
