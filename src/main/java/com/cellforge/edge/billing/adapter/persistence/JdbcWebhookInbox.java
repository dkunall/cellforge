package com.cellforge.edge.billing.adapter.persistence;

import static com.cellforge.edge.billing.adapter.persistence.JdbcSubscriptions.ts;

import com.cellforge.edge.billing.application.WebhookInbox;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcWebhookInbox implements WebhookInbox {

    private final JdbcClient jdbc;

    JdbcWebhookInbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean firstTime(String eventId, String type, Instant at) {
        return jdbc.sql("INSERT INTO webhook_events (event_id, type, received_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
            .params(eventId, type, ts(at)).update() == 1;
    }
}
