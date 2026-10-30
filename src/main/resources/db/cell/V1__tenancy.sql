-- Migrations run as the database owner. The service connects as ${appUser}, which owns nothing and cannot
-- bypass row-level security, so a query that forgets its tenant sees no rows instead of everyone's.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${appUser}') THEN
        EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE',
                       '${appUser}', '${appPassword}');
    ELSE
        EXECUTE format('ALTER ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOBYPASSRLS', '${appUser}', '${appPassword}');
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO ${appUser};
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${appUser};
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO ${appUser};

-- The tenant of the current transaction, or NULL. SET LOCAL leaves '' behind once a transaction ends, and ''
-- does not cast to uuid, hence the nullif.
CREATE FUNCTION current_tenant() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

CREATE TABLE tenants (
    id         uuid PRIMARY KEY,
    slug       text        NOT NULL UNIQUE,
    name       text        NOT NULL,
    status     text        NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'MOVED')),
    created_at timestamptz NOT NULL
);

CREATE TABLE memberships (
    tenant_id    uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id      text        NOT NULL,
    email        text,
    display_name text        NOT NULL,
    role         text        NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'GUEST')),
    joined_at    timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, user_id)
);

ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenants USING (id = current_tenant()) WITH CHECK (id = current_tenant());

ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE memberships FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON memberships
    USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
