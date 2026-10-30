-- Kafka delivers at least once; the event id is what makes a redelivery count zero times.
CREATE TABLE usage_events (
    event_id  uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    at        timestamptz NOT NULL
);

CREATE INDEX usage_events_at ON usage_events (at);

CREATE TABLE usage_hourly (
    tenant_id uuid        NOT NULL,
    hour      timestamptz NOT NULL,
    calls     bigint      NOT NULL,
    errors    bigint      NOT NULL,
    PRIMARY KEY (tenant_id, hour)
);
