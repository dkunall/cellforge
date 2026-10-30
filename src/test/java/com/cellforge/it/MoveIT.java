package com.cellforge.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cellforge.cell.tenancy.application.TenantContext;
import com.cellforge.cell.tenancy.application.TenantContext.Scope;
import com.cellforge.cell.tenancy.application.TenantTransfer;
import com.cellforge.edge.directory.application.CellClient;
import com.cellforge.edge.directory.application.Directory;
import com.cellforge.edge.directory.application.Routes;
import com.cellforge.edge.directory.application.TenantMoves;
import com.cellforge.shared.error.NotFoundException;
import com.cellforge.support.Api;
import com.cellforge.support.Containers;
import com.cellforge.support.Platform;
import com.cellforge.support.Platform.Workspace;
import com.cellforge.support.Race;
import com.cellforge.support.TestJwt;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MoveIT {

    private final Platform platform = Platform.get();

    private Api as(Workspace w, String userId) {
        return platform.edge().as(TestJwt.user(userId)).in(w.slug());
    }

    private Api admin() {
        return platform.edge().as(TestJwt.service("ops", "cellforge.admin"));
    }

    private static String other(String cell) {
        return cell.equals(Platform.CELL_A) ? Platform.CELL_B : Platform.CELL_A;
    }

    private String cellOf(Workspace w) {
        return platform.directoryDb().sql("SELECT cell_id FROM tenants WHERE id = ?").param(w.id()).query(String.class).single();
    }

    /** Owner connection to a cell database, which sees every tenant's rows regardless of RLS. */
    private static JdbcClient cellDb(String cell) {
        return JdbcClient.create(new DriverManagerDataSource(Containers.jdbcUrl(cell.replace('-', '_')),
            Containers.OWNER, Containers.OWNER_PASSWORD));
    }

    private static long rowsOf(String cell, String table, Workspace w) {
        String column = table.equals("tenants") ? "id" : "tenant_id";
        return cellDb(cell).sql("SELECT count(*) FROM " + table + " WHERE " + column + " = ?").param(w.id())
            .query(Long.class).single();
    }

    private record Seeded(String project, String task) {
    }

    private Seeded seed(Workspace w, String member) {
        Api api = as(w, w.ownerId());
        String project = api.post("/v1/projects", Map.of("key", "MV", "name", "Moving")).expect(201).text("id");
        String first = api.post("/v1/projects/" + project + "/tasks", Map.of("title", "First")).expect(201).text("id");
        api.post("/v1/projects/" + project + "/tasks", Map.of("title", "Second")).expect(201);
        api.post("/v1/tasks/" + first + "/move", Map.of("status", "IN_PROGRESS")).expect(200);
        api.post("/v1/tasks/" + first + "/comments", Map.of("body", "Over to you @" + member)).expect(201);
        return new Seeded(project, first);
    }

    private static List<String> kinds(JsonNode activity) {
        return StreamSupport.stream(activity.spliterator(), false).map(a -> a.path("kind").asText()).toList();
    }

    @Test
    void aMovedTenantKeepsItsDataAndTheOldCellKeepsNone() {
        Workspace w = platform.workspace("mover", "ENTERPRISE");
        platform.join(w, "mover-mate", "MEMBER");
        Seeded s = seed(w, "mover-mate");
        Api api = as(w, w.ownerId());
        JsonNode board = api.get("/v1/projects/" + s.project() + "/board").expect(200).body();
        JsonNode comments = api.get("/v1/tasks/" + s.task() + "/comments").expect(200).body();
        List<String> activity = kinds(api.get("/v1/projects/" + s.project() + "/activity").expect(200).body());
        JsonNode unread = as(w, "mover-mate").get("/v1/me/digest").expect(200).body().path("unread");
        String source = cellOf(w);
        String target = other(source);

        Api.Response moved = admin().post("/internal/tenants/" + w.id() + "/move", Map.of("targetCell", target)).expect(200);

        assertThat(moved.text("from")).isEqualTo(source);
        assertThat(moved.text("to")).isEqualTo(target);
        assertThat(moved.body().path("digest").path("tasks").path("rows").asLong()).isEqualTo(2);
        assertThat(moved.body().path("digest").path("memberships").path("rows").asLong()).isEqualTo(2);
        assertThat(cellOf(w)).isEqualTo(target);
        assertThat(api.get("/v1/projects/" + s.project() + "/board").expect(200).body()).isEqualTo(board);
        assertThat(api.get("/v1/tasks/" + s.task() + "/comments").expect(200).body()).isEqualTo(comments);
        assertThat(kinds(api.get("/v1/projects/" + s.project() + "/activity").expect(200).body())).isEqualTo(activity);
        assertThat(as(w, "mover-mate").get("/v1/me/digest").expect(200).body().path("unread")).isEqualTo(unread);
        api.post("/v1/projects/" + s.project() + "/tasks", Map.of("title", "Written after the move")).expect(201);

        for (String table : List.of("tenants", "memberships", "audit_log", "projects", "tasks", "comments", "activity",
                "notifications")) {
            assertThat(rowsOf(source, table, w)).as(table + " left in " + source).isZero();
            assertThat(rowsOf(target, table, w)).as(table + " in " + target).isPositive();
        }
    }

    @Test
    void writesWaitOutTheFreezeWhileReadsCarryOn() {
        Workspace w = platform.workspace("frozen", "ENTERPRISE");
        Api api = as(w, w.ownerId());
        String project = api.post("/v1/projects", Map.of("key", "FZ", "name", "Frozen")).expect(201).text("id");
        TenantTransfer transfer = platform.cellBean(cellOf(w), TenantTransfer.class);

        TenantContext.run(Scope.internal(w.id()), transfer::freeze);
        try {
            Api.Response refused = api.post("/v1/projects/" + project + "/tasks", Map.of("title", "Too soon")).expect(503);
            assertThat(refused.text("code")).isEqualTo("tenant-moving");
            assertThat(refused.header("Retry-After")).isNotBlank();
            api.get("/v1/projects/" + project + "/board").expect(200);
        } finally {
            TenantContext.run(Scope.internal(w.id()), transfer::unfreeze);
        }
        api.post("/v1/projects/" + project + "/tasks", Map.of("title", "Now")).expect(201);
    }

    @Test
    void anEdgeWithAStaleRouteFollowsTheTenantToItsNewCell() {
        Workspace w = platform.workspace("stale", "ENTERPRISE");
        Seeded s = seed(w, "nobody");
        Api api = as(w, w.ownerId());
        api.get("/v1/projects/" + s.project() + "/board").expect(200);
        String target = other(cellOf(w));
        // Another edge replica runs the move, so this edge's route cache still points at the old cell.
        TenantMoves elsewhere = new TenantMoves(platform.edgeBean(Directory.class), platform.edgeBean(CellClient.class),
            new Routes(platform.edgeBean(Directory.class)), platform.clock());

        elsewhere.move(w.id(), target);

        assertThat(api.get("/v1/projects/" + s.project() + "/board").expect(200).body().path("key").asText()).isEqualTo("MV");
        api.post("/v1/projects/" + s.project() + "/tasks", Map.of("title", "Followed")).expect(201);
        assertThat(rowsOf(target, "tasks", w)).isEqualTo(3);
    }

    @Test
    void aFailedCopyPutsEverythingBack() {
        Workspace w = platform.workspace("rollback", "ENTERPRISE");
        Seeded s = seed(w, "nobody");
        String source = cellOf(w);
        // Not accepting, so provisioning in other tests never picks it.
        platform.directoryDb().sql("INSERT INTO cells (id, internal_url, accepting) VALUES ('cell-dead', 'http://127.0.0.1:1', false) "
            + "ON CONFLICT (id) DO NOTHING").update();
        try {
            Api.Response failed = admin().post("/internal/tenants/" + w.id() + "/move", Map.of("targetCell", "cell-dead"))
                .expect(502);
            assertThat(failed.text("code")).isEqualTo("move-failed");
        } finally {
            platform.directoryDb().sql("DELETE FROM cells WHERE id = 'cell-dead'").update();
        }

        assertThat(cellOf(w)).isEqualTo(source);
        assertThat(platform.directoryDb().sql("SELECT status FROM tenants WHERE id = ?").param(w.id())
            .query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(cellDb(source).sql("SELECT status FROM tenants WHERE id = ?").param(w.id()).query(String.class).single())
            .isEqualTo("ACTIVE");
        as(w, w.ownerId()).post("/v1/projects/" + s.project() + "/tasks", Map.of("title", "Still here")).expect(201);
        assertThat(rowsOf(source, "tasks", w)).isEqualTo(3);
    }

    @Test
    void racingMovesOfOneTenantRunOnce() {
        Workspace w = platform.workspace("racer", "ENTERPRISE");
        seed(w, "nobody");
        String source = cellOf(w);
        String target = other(source);

        List<Api.Response> results = Race.run(4, i ->
            admin().post("/internal/tenants/" + w.id() + "/move", Map.of("targetCell", target)));

        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == 409).hasSize(3)
            .allSatisfy(r -> assertThat(r.text("code")).isIn("move-in-progress", "already-in-cell"));
        assertThat(cellOf(w)).isEqualTo(target);
        assertThat(rowsOf(target, "tasks", w)).isEqualTo(2);
        assertThat(rowsOf(source, "tasks", w)).isZero();
    }

    @Test
    void onlyAdminsMoveTenantsAndOnlyToAnotherKnownCell() {
        Workspace w = platform.workspace("guarded", "FREE");
        String path = "/internal/tenants/" + w.id() + "/move";

        platform.edge().as(TestJwt.user("guarded")).post(path, Map.of("targetCell", other(w.cellId()))).expect(403);
        platform.edge().as(TestJwt.service("cell", "cellforge.internal")).post(path, Map.of("targetCell", other(w.cellId())))
            .expect(403);
        assertThat(admin().post(path, Map.of("targetCell", w.cellId())).expect(409).text("code")).isEqualTo("already-in-cell");
        assertThat(admin().post(path, Map.of("targetCell", "cell-z")).expect(400).text("code")).isEqualTo("unknown-cell");
        assertThatThrownBy(() -> TenantContext.run(Scope.internal(w.id()),
            platform.cellBean(other(w.cellId()), TenantTransfer.class)::export)).isInstanceOf(NotFoundException.class);
    }
}
