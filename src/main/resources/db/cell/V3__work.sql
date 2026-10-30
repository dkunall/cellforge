-- Projects, tasks and everything hanging off them. Every table carries tenant_id and the same RLS policy.

CREATE TABLE projects (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    key              text NOT NULL,
    name             text NOT NULL,
    created_by       text NOT NULL,
    created_at       timestamptz NOT NULL,
    archived_at      timestamptz,
    -- Bumped by every change to the board. Doubles as the board ETag and, because writers update this row
    -- first, as the per-project write lock.
    board_version    bigint NOT NULL DEFAULT 0,
    next_task_number int NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, key)
);

-- Guests see nothing by default; a manager opens individual projects to them.
CREATE TABLE project_guests (
    tenant_id  uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    project_id uuid NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id    text NOT NULL,
    PRIMARY KEY (project_id, user_id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES memberships (tenant_id, user_id) ON DELETE CASCADE
);

CREATE TABLE tasks (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    project_id  uuid NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    number      int NOT NULL,
    title       text NOT NULL,
    description text NOT NULL DEFAULT '',
    status      text NOT NULL CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),
    -- Byte order, matching how the application compares base-62 rank strings.
    rank        text COLLATE "C" NOT NULL,
    assignee_id text,
    created_by  text NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     int NOT NULL DEFAULT 1,
    UNIQUE (project_id, number),
    -- Deferrable so a column can be respread in one transaction without tripping over itself midway.
    CONSTRAINT tasks_rank_unique UNIQUE (project_id, status, rank) DEFERRABLE INITIALLY IMMEDIATE
);
CREATE INDEX tasks_assignee ON tasks (tenant_id, assignee_id) WHERE status <> 'DONE';

CREATE TABLE comments (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    task_id    uuid NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    author_id  text NOT NULL,
    body       text NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX comments_task ON comments (task_id, created_at);

CREATE TABLE activity (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    project_id uuid NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    task_id    uuid,
    actor_id   text NOT NULL,
    kind       text NOT NULL,
    details    jsonb NOT NULL DEFAULT '{}',
    at         timestamptz NOT NULL
);
CREATE INDEX activity_project ON activity (project_id, id DESC);

CREATE TABLE notifications (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id    text NOT NULL,
    kind       text NOT NULL CHECK (kind IN ('MENTIONED', 'ASSIGNED')),
    task_id    uuid NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    actor_id   text NOT NULL,
    created_at timestamptz NOT NULL,
    read_at    timestamptz
);
CREATE INDEX notifications_unread ON notifications (tenant_id, user_id, created_at) WHERE read_at IS NULL;

-- One row per (user, key). The row is inserted in the same transaction as the work it guards, so a
-- concurrent retry blocks on the unique index until the first attempt commits or rolls back.
CREATE TABLE idempotency_keys (
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id      text NOT NULL,
    key          text NOT NULL,
    request_hash text NOT NULL,
    status       int,
    body         jsonb,
    created_at   timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, user_id, key)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['projects', 'project_guests', 'tasks', 'comments', 'activity', 'notifications',
                             'idempotency_keys'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant()) '
                       'WITH CHECK (tenant_id = current_tenant())', t);
    END LOOP;
END $$;

REVOKE UPDATE, DELETE ON activity FROM ${appUser};
