package com.cellforge.edge.billing.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Charges are asynchronous like a real processor's: {@link #charge} only starts the attempt, and the outcome
 * arrives later as a signed webhook.
 */
public interface PaymentProvider {

    record Charge(UUID invoiceId, UUID tenantId, long amountCents, String cardLast4, int attempt) {
    }

    /** The card a client-side payment token stands for, as its last four digits. */
    Optional<String> cardFor(String token);

    void charge(Charge charge);
}
