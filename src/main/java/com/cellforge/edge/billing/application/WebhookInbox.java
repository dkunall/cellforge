package com.cellforge.edge.billing.application;

import java.time.Instant;

public interface WebhookInbox {

    /** False when this event id was handled before. */
    boolean firstTime(String eventId, String type, Instant at);
}
