package com.cellforge.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.cellforge.support.Api;
import com.cellforge.support.Platform;
import com.cellforge.support.Platform.Workspace;
import com.cellforge.support.Race;
import com.cellforge.support.TestJwt;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class WorkIT {

    private final Platform platform = Platform.get();

    private Api as(Workspace w, String userId) {
        return platform.edge().as(TestJwt.user(userId)).in(w.slug());
    }

    private String project(Api api, String key) {
        return api.post("/v1/projects", Map.of("key", key, "name", key + " project")).expect(201).text("id");
    }

    private String task(Api api, String projectId, String title) {
        return api.post("/v1/projects/" + projectId + "/tasks", Map.of("title", title)).expect(201).text("id");
    }

    private List<String> column(Api api, String projectId, String status) {
        JsonNode column = api.get("/v1/projects/" + projectId + "/board").expect(200).body().path("columns").path(status);
        return StreamSupport.stream(column.spliterator(), false).map(t -> t.path("id").asText()).toList();
    }

    @Test
    void ofConcurrentEditsAgainstOneVersionExactlyOneWins() {
        Workspace w = platform.workspace("ifm", "FREE");
        Api api = as(w, "ifm");
        String task = task(api, project(api, "IFM"), "Draft");
        String etag = api.get("/v1/tasks/" + task).expect(200).header("ETag");

        List<Api.Response> results = Race.run(8, i ->
            api.patch("/v1/tasks/" + task, Map.of("title", "Edit " + i), Map.of("If-Match", etag)));

        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == 412)
            .hasSize(7).allSatisfy(r -> assertThat(r.text("code")).isEqualTo("version-mismatch"));
        assertThat(api.patch("/v1/tasks/" + task, Map.of("title", "x"), Map.of()).expect(428).text("code"))
            .isEqualTo("if-match-required");
    }

    @Test
    void concurrentRetriesWithOneIdempotencyKeyCreateOneTask() {
        Workspace w = platform.workspace("idem", "FREE");
        Api api = as(w, "idem");
        String project = project(api, "IDEM");
        Map<String, String> key = Map.of("Idempotency-Key", "create-1");

        List<Api.Response> results = Race.run(8, i ->
            api.post("/v1/projects/" + project + "/tasks", Map.of("title", "Once"), key));

        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(results.stream().map(r -> r.text("id")).distinct()).hasSize(1);
        assertThat(results).filteredOn(r -> "true".equals(r.header("Idempotent-Replayed"))).hasSize(7);
        assertThat(column(api, project, "TODO")).hasSize(1);

        Api.Response reused = api.post("/v1/projects/" + project + "/tasks", Map.of("title", "Different"), key)
            .expect(422);
        assertThat(reused.text("code")).isEqualTo("idempotency-key-reused");
    }

    @Test
    void concurrentMovesIntoOneColumnKeepADistinctOrder() {
        Workspace w = platform.workspace("moves", "FREE");
        Api api = as(w, "moves");
        String project = project(api, "MOV");
        List<String> tasks = IntStream.range(0, 12).mapToObj(i -> task(api, project, "Card " + i)).toList();

        List<Api.Response> results = Race.run(12, i ->
            api.post("/v1/tasks/" + tasks.get(i) + "/move", Map.of("status", "DONE")));

        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
        JsonNode done = api.get("/v1/projects/" + project + "/board").body().path("columns").path("DONE");
        List<String> ranks = StreamSupport.stream(done.spliterator(), false).map(t -> t.path("rank").asText()).toList();
        assertThat(ranks).hasSize(12).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void repeatedlyInsertingIntoOneGapRespreadsTheColumnWithoutLosingOrder() {
        // ENTERPRISE only for its burst allowance: this test sends a few hundred requests in a tight loop.
        Workspace w = platform.workspace("spread", "ENTERPRISE");
        Api api = as(w, "spread");
        String project = project(api, "SPR");
        String first = task(api, project, "First");
        String a = task(api, project, "A");
        String b = task(api, project, "B");

        // Alternately dropping a and b straight after "first" halves the same gap every time, which is the
        // pattern that makes fractional keys grow until the column has to be respread.
        int maxLength = 0;
        for (int i = 0; i < 150; i++) {
            String moving = i % 2 == 0 ? a : b;
            String other = i % 2 == 0 ? b : a;
            api.post("/v1/tasks/" + moving + "/move", Map.of("status", "TODO", "afterId", first)).expect(200);
            JsonNode todo = api.get("/v1/projects/" + project + "/board").body().path("columns").path("TODO");
            assertThat(todo.findValuesAsText("id")).containsExactly(first, moving, other);
            for (JsonNode t : todo) {
                maxLength = Math.max(maxLength, t.path("rank").asText().length());
            }
        }
        assertThat(maxLength).isLessThanOrEqualTo(24);
    }

    @Test
    void theBoardAnswersNotModifiedUntilSomethingChanges() {
        Workspace w = platform.workspace("etag", "FREE");
        Api api = as(w, "etag");
        String project = project(api, "ETG");
        String etag = api.get("/v1/projects/" + project + "/board").expect(200).header("ETag");

        api.get("/v1/projects/" + project + "/board", Map.of("If-None-Match", etag)).expect(304);
        task(api, project, "Change");
        api.get("/v1/projects/" + project + "/board", Map.of("If-None-Match", etag)).expect(200);
    }

    @Test
    void guestsSeeOnlyProjectsOpenedToThemAndCanOnlyComment() {
        Workspace w = platform.workspace("guest-host", "PRO");
        Api owner = as(w, "guest-host");
        String open = project(owner, "OPEN");
        String closed = project(owner, "SHUT");
        String openTask = task(owner, open, "Visible");
        String closedTask = task(owner, closed, "Hidden");
        platform.join(w, "client", "GUEST");
        owner.put("/v1/projects/" + open + "/guests/client", null).expect(204);
        Api guest = as(w, "client");

        assertThat(guest.get("/v1/projects").expect(200).body().findValuesAsText("id")).containsExactly(open);
        guest.get("/v1/projects/" + closed).expect(404);
        guest.get("/v1/tasks/" + closedTask).expect(404);
        guest.post("/v1/tasks/" + openTask + "/comments", Map.of("body", "Looks good")).expect(201);
        assertThat(guest.post("/v1/projects/" + open + "/tasks", Map.of("title", "Sneaky")).expect(403).text("code"))
            .isEqualTo("permission-denied");
    }

    @Test
    void idsFromAnotherWorkspaceAreNotFoundEvenForSomeoneInBoth() {
        Workspace mine = platform.workspace("both", "FREE");
        Workspace other = platform.workspace("other-owner", "FREE");
        platform.join(other, "both", "MEMBER");
        Api inOther = as(other, "both");
        String theirProject = project(inOther, "THEIRS");
        String theirTask = task(inOther, theirProject, "Theirs");
        Api inMine = as(mine, "both");

        inMine.get("/v1/tasks/" + theirTask).expect(404);
        inMine.get("/v1/projects/" + theirProject + "/board").expect(404);
        inMine.post("/v1/tasks/" + theirTask + "/comments", Map.of("body", "hi")).expect(404);
        inMine.send(HttpMethod.DELETE, "/v1/tasks/" + theirTask, null, Map.of()).expect(404);
        inOther.get("/v1/tasks/" + theirTask).expect(200);
    }

    @Test
    void mentionsAndAssignmentsLandInTheDigest() {
        Workspace w = platform.workspace("mention-host", "FREE");
        platform.join(w, "bob", "MEMBER");
        Api host = as(w, "mention-host");
        String project = project(host, "MEN");
        String task = host.post("/v1/projects/" + project + "/tasks", Map.of("title", "Review", "assigneeId", "bob"))
            .expect(201).text("id");
        host.post("/v1/tasks/" + task + "/comments", Map.of("body", "@bob can you take a look? cc @mention-host"))
            .expect(201);

        JsonNode digest = as(w, "bob").get("/v1/me/digest").expect(200).body();
        assertThat(digest.path("unreadCount").asInt()).isEqualTo(2);
        assertThat(digest.path("assigned").findValuesAsText("id")).containsExactly(task);
        assertThat(as(w, "mention-host").get("/v1/me/digest").body().path("unreadCount").asInt()).isZero();
    }
}
