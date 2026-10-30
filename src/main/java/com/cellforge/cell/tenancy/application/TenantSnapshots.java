package com.cellforge.cell.tenancy.application;

import java.util.Map;

/** Reads and writes every row a tenant owns in this cell. Runs inside the tenant's internal scope. */
public interface TenantSnapshots {

    /** Row count and content hash of one table, comparable across cells. */
    record TableDigest(long rows, String md5) {
    }

    /** Table name to a JSON array of its rows, in the order they must be restored. */
    Map<String, String> export();

    void restore(Map<String, String> tables);

    Map<String, TableDigest> digest();
}
