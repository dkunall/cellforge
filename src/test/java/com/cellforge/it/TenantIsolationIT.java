package com.cellforge.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.cellforge.support.Api;
import com.cellforge.support.Platform;
import com.cellforge.support.Platform.Workspace;
import com.cellforge.support.TestJwt;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** Everything a user of one workspace might try against another's ids, through the public API. */
class TenantIsolationIT {

    private final Platform platform = Platform.get();

    private Api as(Workspace w, String userId) {
        return platform.edge().as(TestJwt.user(userId)).in(w.slug());
    }

    private record Victim(Workspace w, String project, String task) {
    }

    private Victim victim(String owner) {
        Workspace w = platform.workspace(owner, "ENTERPRISE");
        Api api = as(w, owner);
        String project = api.post("/v1/projects", Map.of("key", "SEC", "name", "Secret")).expect(201).text("id");
        String task = api.post("/v1/projects/" + project + "/tasks", Map.of("title", "Secret plan")).expect(201).text("id");
        api.post("/v1/tasks/" + task + "/comments", Map.of("body", "Do not share")).expect(201);
        return new Victim(w, project, task);
    }

    @Test
    void anotherWorkspacesIdsLookLikeTheyDoNotExist() {
        Victim v = victim("iso-victim");
        Workspace mine = platform.workspace("iso-attacker", "ENTERPRISE");
        Api me = as(mine, "iso-attacker");
        String etag = as(v.w(), "iso-victim").get("/v1/tasks/" + v.task()).expect(200).header("ETag");

        List<Api.Response> attempts = List.of(
            me.get("/v1/projects/" + v.project()),
            me.get("/v1/projects/" + v.project() + "/board"),
            me.get("/v1/projects/" + v.project() + "/activity"),
            me.post("/v1/projects/" + v.project() + "/tasks", Map.of("title", "Planted")),
            me.post("/v1/projects/" + v.project() + "/archive", Map.of()),
            me.get("/v1/tasks/" + v.task()),
            me.get("/v1/tasks/" + v.task() + "/comments"),
            me.post("/v1/tasks/" + v.task() + "/comments", Map.of("body", "Planted")),
            me.patch("/v1/tasks/" + v.task(), Map.of("title", "Hijacked"), Map.of("If-Match", etag)),
            me.post("/v1/tasks/" + v.task() + "/move", Map.of("status", "DONE")),
            me.delete("/v1/tasks/" + v.task()));

        assertThat(attempts).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(404);
            assertThat(r.body().toString()).doesNotContain("Secret", "Do not share");
        });
        Api owner = as(v.w(), "iso-victim");
        assertThat(owner.get("/v1/tasks/" + v.task()).expect(200).text("title")).isEqualTo("Secret plan");
        assertThat(owner.get("/v1/tasks/" + v.task() + "/comments").expect(200).body()).hasSize(1);
    }

    @Test
    void aUserCannotEnterAWorkspaceTheyDoNotBelongTo() {
        Victim v = victim("iso-door");
        Api stranger = platform.edge().as(TestJwt.user("iso-stranger")).in(v.w().slug());

        assertThat(stranger.get("/v1/projects/" + v.project() + "/board").expect(403).text("code")).isEqualTo("not-a-member");
        assertThat(stranger.post("/v1/projects", Map.of("key", "IN", "name", "In")).expect(403).text("code"))
            .isEqualTo("not-a-member");
        assertThat(stranger.get("/v1/me/digest").expect(403).text("code")).isEqualTo("not-a-member");
    }

    @Test
    void aForgedTenantHeaderIsIgnored() {
        Victim v = victim("iso-header");
        Workspace mine = platform.workspace("iso-forger", "ENTERPRISE");
        Api forged = as(mine, "iso-forger").with("X-Tenant-Id", v.w().id().toString());

        forged.get("/v1/projects/" + v.project()).expect(404);
        forged.get("/v1/tasks/" + v.task()).expect(404);
        forged.post("/v1/projects/" + v.project() + "/tasks", Map.of("title", "Planted")).expect(404);
    }

    @Test
    void anInviteOnlyWorksInTheWorkspaceThatIssuedIt() {
        Victim v = victim("iso-invite");
        String token = platform.invite(v.w(), "iso-guest", "MEMBER");
        Workspace other = platform.workspace("iso-other", "ENTERPRISE");

        Api.Response wrongDoor = platform.edge().as(TestJwt.user("iso-guest")).in(other.slug())
            .post("/v1/invites/accept", Map.of("token", token));

        assertThat(wrongDoor.status()).isIn(403, 404);
        platform.edge().as(TestJwt.user("iso-guest")).in(other.slug()).get("/v1/me/digest").expect(403);
    }

    @Test
    void oneIdempotencyKeyInTwoWorkspacesMakesTwoTasks() {
        Victim a = victim("iso-key-a");
        Victim b = victim("iso-key-b");
        Map<String, String> key = Map.of("Idempotency-Key", "shared-key");

        Api.Response first = as(a.w(), "iso-key-a").post("/v1/projects/" + a.project() + "/tasks", Map.of("title", "A"), key)
            .expect(201);
        Api.Response second = as(b.w(), "iso-key-b").post("/v1/projects/" + b.project() + "/tasks", Map.of("title", "B"), key)
            .expect(201);

        assertThat(second.text("id")).isNotEqualTo(first.text("id"));
        assertThat(second.header("Idempotent-Replayed")).isNotEqualTo("true");
        assertThat(second.text("title")).isEqualTo("B");
    }

    @Test
    void theInternalApiIsClosedToUsers() {
        Victim v = victim("iso-internal");
        Api user = platform.edge().as(TestJwt.user("iso-internal"));

        user.send(HttpMethod.POST, "/internal/billing/run", Map.of(), Map.of()).expect(403);
        user.post("/internal/tenants/" + v.w().id() + "/move", Map.of("targetCell", Platform.CELL_A)).expect(403);
        user.get("/internal/tenants/" + v.w().id() + "/entitlements").expect(403);
    }
}
