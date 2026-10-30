CREATE TABLE subscriptions (
    tenant_id     uuid PRIMARY KEY REFERENCES tenants (id),
    plan_code     text NOT NULL REFERENCES plans (code),
    status        text NOT NULL CHECK (status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELED')),
    trial_ends_at timestamptz,
    period_start  timestamptz NOT NULL,
    period_end    timestamptz NOT NULL,
    -- Set when a payment fails; writes stay open until then.
    grace_until   timestamptz,
    card_last4    text,
    version       int NOT NULL DEFAULT 0
);
CREATE INDEX subscriptions_due ON subscriptions (period_end);
