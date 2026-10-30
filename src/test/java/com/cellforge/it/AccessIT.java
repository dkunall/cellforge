package com.cellforge.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.cellforge.support.Api;
import com.cellforge.support.Platform;
import com.cellforge.support.Platform.Workspace;
import com.cellforge.support.Race;
import com.cellforge.support.TestJwt;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class AccessIT {

    private final Platform platform = Platform.get();

    private Api as(Workspace w, String userId) {
        return platform.edge().as(TestJwt.user(userId)).in(w.slug());
    }

    @Test
    void concurrentAcceptsNeverExceedTheSeatLimit() {
        // FREE has 3 seats and the owner holds one, so of six people accepting at once exactly two get in.
        Workspace w = platform.workspace("seats-owner", "FREE");
        List<String> tokens = IntStream.range(0, 6).mapToObj(i -> platform.invite(w, "racer" + i, "MEMBER")).toList();

        List<Api.Response> results = Race.run(6, i ->
            as(w, "racer" + i).post("/v1/invites/accept", Map.of("token", tokens.get(i))));

        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(2);
        assertThat(results).filteredOn(r -> r.status() == 422)
            .hasSize(4).allSatisfy(r -> assertThat(r.text("code")).isEqualTo("seat-limit"));
        assertThat(as(w, "seats-owner").get("/v1/members").expect(200).body()).hasSize(3);
    }

    @Test
    void acceptingTwiceIsHarmlessAndTheTokenIsBoundToTheInvitedEmail() {
        Workspace w = platform.workspace("inv-owner", "FREE");
        String token = platform.invite(w, "joiner", "MEMBER");

        Api.Response stranger = platform.edge().as(TestJwt.user("joiner", "someone-else@example.test")).in(w.slug())
            .post("/v1/invites/accept", Map.of("token", token)).expect(403);
        as(w, "joiner").post("/v1/invites/accept", Map.of("token", token)).expect(200);
        Api.Response again = as(w, "joiner").post("/v1/invites/accept", Map.of("token", token)).expect(200);

        assertThat(stranger.text("code")).isEqualTo("invite-email-mismatch");
        assertThat(again.text("userId")).isEqualTo("joiner");
        assertThat(as(w, "inv-owner").get("/v1/members").body()).hasSize(2);
    }

    @Test
    void guestsNeedAPlanThatHasThemAndDoNotTakeSeats() {
        Workspace free = platform.workspace("g-free", "FREE");
        Api.Response refused = as(free, "g-free")
            .post("/v1/invites", Map.of("email", "visitor@example.test", "role", "GUEST")).expect(422);
        assertThat(refused.text("code")).isEqualTo("feature-not-in-plan");

        Workspace pro = platform.workspace("g-pro", "PRO");
        platform.join(pro, "visitor", "GUEST");
        Api.Response members = as(pro, "g-pro").get("/v1/members").expect(200);
        assertThat(members.body().findValuesAsText("role")).containsExactlyInAnyOrder("OWNER", "GUEST");
    }

    @Test
    void membersCannotPromoteThemselvesOrInvite() {
        Workspace w = platform.workspace("rank-owner", "FREE");
        platform.join(w, "plain", "MEMBER");

        assertThat(as(w, "plain").send(HttpMethod.PATCH, "/v1/members/plain",
            Map.of("role", "ADMIN"), Map.of()).expect(403).text("code")).isEqualTo("permission-denied");
        assertThat(as(w, "plain").post("/v1/invites", Map.of("email", "x@example.test", "role", "MEMBER"))
            .expect(403).text("code")).isEqualTo("permission-denied");
    }

    @Test
    void theAuditLogIsAnEnterpriseFeatureAndRecordsJoins() {
        Workspace free = platform.workspace("audit-free", "FREE");
        assertThat(as(free, "audit-free").get("/v1/audit").expect(422).text("code")).isEqualTo("feature-not-in-plan");

        Workspace big = platform.workspace("audit-big", "ENTERPRISE");
        platform.join(big, "auditee", "MEMBER");
        Api.Response audit = as(big, "audit-big").get("/v1/audit").expect(200);
        assertThat(audit.body().findValuesAsText("action")).contains("member.joined");
    }
}
