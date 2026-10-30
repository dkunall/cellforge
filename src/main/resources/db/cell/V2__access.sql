CREATE TABLE invites (
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    email       text        NOT NULL,
    role        text        NOT NULL CHECK (role IN ('ADMIN', 'MEMBER', 'GUEST')),
    -- Only a hash is kept; the token itself is shown once to whoever sends the invite.
    token_hash  text        NOT NULL UNIQUE,
    invited_by  text        NOT NULL,
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    accepted_by text,
    accepted_at timestamptz,
    revoked_at  timestamptz
);

CREATE UNIQUE INDEX invites_one_open_per_email ON invites (tenant_id, lower(email))
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE TABLE audit_log (
    id        uuid PRIMARY KEY,
    tenant_id uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    actor_id  text        NOT NULL,
    action    text        NOT NULL,
    target    text,
    details   jsonb       NOT NULL DEFAULT '{}',
    at        timestamptz NOT NULL
);

CREATE INDEX audit_log_by_time ON audit_log (tenant_id, at DESC);

ALTER TABLE invites ENABLE ROW LEVEL SECURITY;
ALTER TABLE invites FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invites USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());

-- Append-only for the runtime role: it may write entries but never rewrite or delete them.
REVOKE UPDATE, DELETE ON audit_log FROM ${appUser};
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_log USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
