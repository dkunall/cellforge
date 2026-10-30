package com.cellforge.cell.tenancy.domain;

public enum TenantStatus {
    ACTIVE,
    /** Copying to another cell: reads are served, writes wait for the move to finish. */
    FROZEN,
    /** Lives in another cell now; this cell keeps nothing but the marker until cleanup. */
    MOVED
}
