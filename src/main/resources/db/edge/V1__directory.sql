-- The control plane's own database. Unlike the cells it holds no customer content, only where each tenant
-- lives and what it pays for, so it runs as a single role without row-level security.

CREATE TABLE plans (
    code                 text PRIMARY KEY,
    name                 text NOT NULL,
    seat_price_cents     int NOT NULL,
    trial_days           int NOT NULL,
    max_seats            int NOT NULL,
    max_projects         int NOT NULL,
    requests_per_minute  int NOT NULL,
    burst                int NOT NULL,
    monthly_api_quota    bigint NOT NULL,
    statement_timeout_ms int NOT NULL,
    max_concurrent       int NOT NULL,
    features             text[] NOT NULL,
    -- Graduated API pricing: [{"upTo": n | null, "centsPer1000": c}], applied in order to the month's calls.
    api_tiers            jsonb NOT NULL
);

-- Plans are data so pricing can change without a deploy. These are the demo plans.
INSERT INTO plans VALUES
    ('FREE', 'Free', 0, 0, 3, 2, 120, 30, 20000, 2000, 4, '{}',
     '[{"upTo": null, "centsPer1000": 0}]'),
    ('PRO', 'Pro', 800, 14, 50, 50, 600, 100, 1000000, 5000, 16, '{guests}',
     '[{"upTo": 100000, "centsPer1000": 0}, {"upTo": 500000, "centsPer1000": 40}, {"upTo": null, "centsPer1000": 25}]'),
    ('ENTERPRISE', 'Enterprise', 1500, 0, 1000, 1000, 3000, 500, 10000000, 15000, 64, '{guests,audit_log}',
     '[{"upTo": 1000000, "centsPer1000": 0}, {"upTo": null, "centsPer1000": 20}]');

CREATE TABLE cells (
    id           text PRIMARY KEY,
    internal_url text NOT NULL,
    accepting    boolean NOT NULL DEFAULT true
);

CREATE TABLE tenants (
    id            uuid PRIMARY KEY,
    slug          text NOT NULL UNIQUE,
    name          text NOT NULL,
    cell_id       text NOT NULL REFERENCES cells (id),
    -- PROVISIONING until the cell confirms; MOVING while a move holds the tenant's writes.
    status        text NOT NULL CHECK (status IN ('PROVISIONING', 'ACTIVE', 'MOVING')),
    owner_user_id text NOT NULL,
    created_at    timestamptz NOT NULL
);
CREATE INDEX tenants_cell ON tenants (cell_id);
