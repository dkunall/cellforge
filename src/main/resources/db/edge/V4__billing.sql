-- Money owed back to the tenant after a downgrade; taken off the next period invoice.
ALTER TABLE subscriptions ADD COLUMN credit_cents bigint NOT NULL DEFAULT 0 CHECK (credit_cents >= 0);

CREATE TABLE invoices (
    id           uuid PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenants (id),
    kind         text        NOT NULL CHECK (kind IN ('PERIOD', 'PRORATION')),
    plan_code    text        NOT NULL REFERENCES plans (code),
    period_start timestamptz NOT NULL,
    period_end   timestamptz NOT NULL,
    seats        int         NOT NULL,
    calls        bigint      NOT NULL,
    seat_cents   bigint      NOT NULL,
    usage_cents  bigint      NOT NULL,
    credit_cents bigint      NOT NULL,
    total_cents  bigint      NOT NULL CHECK (total_cents >= 0),
    status       text        NOT NULL CHECK (status IN ('OPEN', 'PAID', 'FAILED')),
    attempts     int         NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    paid_at      timestamptz
);

-- Closing the same period twice (two sweepers, a retried admin call) must not bill twice.
CREATE UNIQUE INDEX invoices_one_per_period ON invoices (tenant_id, period_start) WHERE kind = 'PERIOD';
CREATE INDEX invoices_tenant ON invoices (tenant_id, created_at DESC);

CREATE TABLE plan_changes (
    tenant_id       uuid        NOT NULL REFERENCES tenants (id),
    idempotency_key text        NOT NULL,
    request_hash    text        NOT NULL,
    from_plan       text        NOT NULL,
    to_plan         text        NOT NULL,
    prorated_cents  bigint      NOT NULL,
    invoice_id      uuid,
    at              timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, idempotency_key)
);

CREATE TABLE webhook_events (
    event_id    text PRIMARY KEY,
    type        text        NOT NULL,
    received_at timestamptz NOT NULL
);
