package com.cellforge.edge.directory.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * New tenants go to the accepting cell with the fewest tenants. Tenant count is a crude proxy for load, but it
 * is the only signal that exists before a tenant has any traffic; moves fix the outliers later.
 */
public final class Placement {

    private Placement() {
    }

    public static Optional<CellLoad> choose(List<CellLoad> cells) {
        return cells.stream()
            .filter(CellLoad::accepting)
            .min(Comparator.comparingLong(CellLoad::tenants).thenComparing(CellLoad::cellId));
    }
}
