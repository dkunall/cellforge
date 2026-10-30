package com.cellforge.edge.directory.application;

import java.util.Map;
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

    record TableDigest(long rows, String md5) {
    }

    record Snapshot(Map<String, String> tables, Map<String, TableDigest> digest) {
    }

    /** One step of moving a tenant out of or into a cell; see TenantMoves for the order. */
    enum TransferStep { FREEZE, UNFREEZE, ACTIVATE, MOVED, PURGE }

    void transfer(String cellUrl, UUID tenantId, TransferStep step);

    Snapshot export(String cellUrl, UUID tenantId);

    Map<String, TableDigest> restore(String cellUrl, UUID tenantId, Map<String, String> tables);
}
