package com.cellforge.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.cellforge.support.Api;
import com.cellforge.support.Platform;
import com.cellforge.support.Platform.Workspace;
import com.cellforge.support.TestJwt;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProvisioningIT {

    private final Platform platform = Platform.get();

    @Test
    void creatingAWorkspaceMakesTheCreatorItsOwnerThroughTheEdge() {
        Workspace w = platform.workspace("ada", "FREE");

        Api.Response members = platform.edge().as(TestJwt.user("ada")).in(w.slug()).get("/v1/members").expect(200);

        assertThat(members.body()).hasSize(1);
        assertThat(members.body().get(0).path("userId").asText()).isEqualTo("ada");
        assertThat(members.body().get(0).path("role").asText()).isEqualTo("OWNER");
    }

    @Test
    void repeatingTheCreateReturnsTheSameWorkspaceButOnlyToItsOwner() {
        Workspace w = platform.workspace("grace", "FREE");
        Map<String, String> again = Map.of("slug", w.slug(), "name", "Workspace " + w.slug(), "plan", "FREE");

        Api.Response retry = platform.edge().as(TestJwt.user("grace")).post("/v1/tenants", again).expect(200);
        Api.Response squatter = platform.edge().as(TestJwt.user("mallory")).post("/v1/tenants", again).expect(409);

        assertThat(retry.text("id")).isEqualTo(w.id().toString());
        assertThat(squatter.text("code")).isEqualTo("slug-taken");
    }

    @Test
    void newWorkspacesSpreadAcrossCells() {
        Set<String> cells = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            cells.add(platform.workspace("spread-" + i, "FREE").cellId());
        }
        assertThat(cells).containsExactlyInAnyOrder(Platform.CELL_A, Platform.CELL_B);
    }

    @Test
    void requestsNeedAKnownWorkspace() {
        Api api = platform.edge().as(TestJwt.user("ada"));

        assertThat(api.get("/v1/members").expect(400).text("code")).isEqualTo("tenant-required");
        assertThat(api.in("no-such-workspace").get("/v1/members").expect(404).text("code")).isEqualTo("tenant-not-found");
    }

    @Test
    void outsidersAreRefusedByTheCell() {
        Workspace w = platform.workspace("linus", "FREE");

        Api.Response outsider = platform.edge().as(TestJwt.user("eve")).in(w.slug()).get("/v1/members").expect(403);

        assertThat(outsider.text("code")).isEqualTo("not-a-member");
    }

    @Test
    void tokensWithoutTheUserScopeAreRejectedAtTheEdge() {
        Workspace w = platform.workspace("ken", "FREE");

        platform.edge().in(w.slug()).get("/v1/members").expect(401);
        platform.edge().as(TestJwt.service("someone", "cellforge.internal")).in(w.slug()).get("/v1/members").expect(403);
    }
}
