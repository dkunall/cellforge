package com.cellforge.edge.billing.application;

import com.cellforge.edge.billing.domain.Invoice;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Invoices {

    /** False when that tenant's period already has an invoice. */
    boolean insert(Invoice invoice);

    Optional<Invoice> find(UUID id);

    List<Invoice> forTenant(UUID tenantId, int limit);

    List<Invoice> failed(UUID tenantId);

    long unpaid(UUID tenantId);

    int nextAttempt(UUID id);

    void markPaid(UUID id, Instant at);

    void markFailed(UUID id);
}
