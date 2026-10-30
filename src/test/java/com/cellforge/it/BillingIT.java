package com.cellforge.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cellforge.edge.billing.domain.WebhookSignature;
import com.cellforge.support.Api;
import com.cellforge.support.Platform;
import com.cellforge.support.Platform.Workspace;
import com.cellforge.support.Race;
import com.cellforge.support.TestJwt;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The clock is shared by every integration test, so each test only moves it forward and only looks at
 * its own workspaces. Webhooks are signed with the clock, so a test waits for its payments to land before
 * moving time again.
 */
class BillingIT {

    private final Platform platform = Platform.get();

    private Api owner(Workspace w) {
        return platform.edge().as(TestJwt.user(w.ownerId())).in(w.slug());
    }

    private Api admin() {
        return platform.edge().as(TestJwt.service("ops", "cellforge.admin"));
    }

    private static Map<String, String> key(String key) {
        return Map.of("Idempotency-Key", key);
    }

    private List<JsonNode> invoices(Workspace w) {
        List<JsonNode> out = new ArrayList<>();
        owner(w).get("/v1/billing/invoices").expect(200).body().forEach(out::add);
        return out;
    }

    private JsonNode invoice(Workspace w, String kind) {
        return invoices(w).stream().filter(i -> i.path("kind").asText().equals(kind)).findFirst().orElseThrow();
    }

    private void awaitInvoice(Workspace w, String kind, String status) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(invoices(w)).anySatisfy(i -> {
                assertThat(i.path("kind").asText()).isEqualTo(kind);
                assertThat(i.path("status").asText()).isEqualTo(status);
            }));
    }

    @Test
    void onlyOwnersAndAdminsSeeBilling() {
        Workspace w = platform.workspace("bill-roles", "FREE");
        platform.join(w, "bill-roles-member", "MEMBER");

        JsonNode overview = owner(w).get("/v1/billing").expect(200).body();
        assertThat(overview.path("plan").asText()).isEqualTo("FREE");
        assertThat(overview.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(overview.path("seats").asLong()).isEqualTo(2);

        Api member = platform.edge().as(TestJwt.user("bill-roles-member")).in(w.slug());
        assertThat(member.get("/v1/billing").expect(403).text("code")).isEqualTo("permission-denied");
        Api stranger = platform.edge().as(TestJwt.user("bill-roles-stranger")).in(w.slug());
        assertThat(stranger.get("/v1/billing").expect(403).text("code")).isEqualTo("not-a-member");
    }

    @Test
    void racingRetriesOfOnePlanChangeChargeOnce() {
        Workspace w = platform.workspace("bill-race", "FREE");
        owner(w).put("/v1/billing/payment-method", Map.of("token", "tok_visa")).expect(200);

        List<Api.Response> responses = Race.run(6, i ->
            owner(w).post("/v1/billing/plan", Map.of("plan", "PRO"), key("upgrade-1")));

        assertThat(responses).allSatisfy(r -> r.expect(200));
        assertThat(responses).filteredOn(r -> r.header("Idempotent-Replayed").equals("false")).hasSize(1);
        assertThat(responses.stream().map(r -> r.body().path("invoice").path("id").asText()).distinct()).hasSize(1);
        // One seat moving from 0 to 800 cents with all but a few seconds of the period left.
        assertThat(responses.get(0).body().path("proratedCents").asLong()).isEqualTo(800);

        awaitInvoice(w, "PRORATION", "PAID");
        assertThat(invoices(w)).hasSize(1);
        assertThat(owner(w).get("/v1/billing").expect(200).text("plan")).isEqualTo("PRO");

        assertThat(owner(w).post("/v1/billing/plan", Map.of("plan", "ENTERPRISE"), key("upgrade-1")).expect(422)
            .text("code")).isEqualTo("idempotency-key-reused");
        assertThat(owner(w).post("/v1/billing/plan", Map.of("plan", "PRO"), key("upgrade-2")).expect(409)
            .text("code")).isEqualTo("already-on-plan");
        assertThat(owner(w).post("/v1/billing/plan", Map.of("plan", "ENTERPRISE"), Map.of()).expect(428)
            .text("code")).isEqualTo("idempotency-key-required");
    }

    @Test
    void aDowngradeMustFitTheSeatsInUse() {
        Workspace w = platform.workspace("bill-seats", "ENTERPRISE");
        for (int i = 0; i < 3; i++) {
            platform.join(w, "bill-seats-" + i, "MEMBER");
        }

        Api.Response refused = owner(w).post("/v1/billing/plan", Map.of("plan", "FREE"), key("down")).expect(409);

        assertThat(refused.text("code")).isEqualTo("too-many-seats");
        assertThat(owner(w).get("/v1/billing").expect(200).text("plan")).isEqualTo("ENTERPRISE");
    }

    @Test
    void aDowngradeCreditComesOffTheNextInvoiceAndPeriodsCloseOnce() {
        Workspace w = platform.workspace("bill-close", "ENTERPRISE");
        owner(w).put("/v1/billing/payment-method", Map.of("token", "tok_visa")).expect(200);

        JsonNode change = owner(w).post("/v1/billing/plan", Map.of("plan", "PRO"), key("down")).expect(200).body();
        assertThat(change.path("proratedCents").asLong()).isEqualTo(-700);
        assertThat(owner(w).get("/v1/billing").expect(200).body().path("creditCents").asLong()).isEqualTo(700);

        platform.clock().advance(Duration.ofDays(31));
        admin().post("/internal/billing/run", Map.of()).expect(200);

        awaitInvoice(w, "PERIOD", "PAID");
        JsonNode first = invoice(w, "PERIOD");
        assertThat(first.path("planCode").asText()).isEqualTo("PRO");
        assertThat(first.path("seats").asInt()).isEqualTo(1);
        assertThat(first.path("seatCents").asLong()).isEqualTo(800);
        assertThat(first.path("creditCents").asLong()).isEqualTo(700);
        assertThat(first.path("totalCents").asLong()).isEqualTo(100);
        JsonNode overview = owner(w).get("/v1/billing").expect(200).body();
        assertThat(overview.path("creditCents").asLong()).isZero();
        assertThat(overview.path("periodStart").asText()).isEqualTo(first.path("periodEnd").asText());

        platform.clock().advance(Duration.ofDays(30));
        Race.run(4, i -> admin().post("/internal/billing/run", Map.of()).expect(200));
        admin().post("/internal/billing/run", Map.of()).expect(200);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(invoices(w)).filteredOn(i -> i.path("kind").asText().equals("PERIOD"))
                .hasSize(2).allSatisfy(i -> assertThat(i.path("status").asText()).isEqualTo("PAID")));
    }

    @Test
    void aFailedPaymentLeadsToSuspensionAndANewCardRecovers() {
        Workspace w = platform.workspace("bill-dunning", "ENTERPRISE");
        owner(w).put("/v1/billing/payment-method", Map.of("token", "tok_declined")).expect(200);

        platform.clock().advance(Duration.ofDays(31));
        admin().post("/internal/billing/run", Map.of()).expect(200);
        awaitInvoice(w, "PERIOD", "FAILED");

        JsonNode overdue = owner(w).get("/v1/billing").expect(200).body();
        assertThat(overdue.path("status").asText()).isEqualTo("PAST_DUE");
        assertThat(overdue.path("graceUntil").isNull()).isFalse();
        // Grace means the team keeps working while someone sorts out the card.
        owner(w).post("/v1/projects", Map.of("key", "DUN", "name", "During grace")).expect(201);

        platform.clock().advance(Duration.ofDays(8));
        admin().post("/internal/billing/run", Map.of()).expect(200);
        assertThat(owner(w).get("/v1/billing").expect(200).text("status")).isEqualTo("SUSPENDED");
        assertThat(owner(w).post("/v1/projects", Map.of("key", "SUS", "name", "Suspended")).expect(402).text("code"))
            .isEqualTo("tenant-suspended");
        owner(w).get("/v1/projects").expect(200);

        owner(w).put("/v1/billing/payment-method", Map.of("token", "tok_visa")).expect(200);
        awaitInvoice(w, "PERIOD", "PAID");
        assertThat(owner(w).get("/v1/billing").expect(200).text("status")).isEqualTo("ACTIVE");
        owner(w).post("/v1/projects", Map.of("key", "BAK", "name", "Back")).expect(201);
    }

    @Test
    void webhooksMustBeSignedAndAreHandledOnce() {
        Workspace w = platform.workspace("bill-hooks", "FREE");
        owner(w).put("/v1/billing/payment-method", Map.of("token", "tok_visa")).expect(200);
        owner(w).post("/v1/billing/plan", Map.of("plan", "PRO"), key("up")).expect(200);
        awaitInvoice(w, "PRORATION", "PAID");
        String invoiceId = invoice(w, "PRORATION").path("id").asText();

        String body = "{\"id\":\"evt_test_" + UUID.randomUUID() + "\",\"type\":\"payment.failed\",\"data\":{\"invoiceId\":\""
            + invoiceId + "\"}}";
        byte[] secret = Platform.WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8);
        Api hooks = platform.edge();

        String forged = WebhookSignature.header("not-the-secret".getBytes(StandardCharsets.UTF_8),
            platform.clock().instant(), body);
        assertThat(hooks.post("/webhooks/payments", body, Map.of("Payment-Signature", forged)).expect(400)
            .text("code")).isEqualTo("invalid-signature");

        // A failure reported after the invoice was paid (a stale retry, say) must not undo the payment.
        String signed = WebhookSignature.header(secret, platform.clock().instant(), body);
        hooks.post("/webhooks/payments", body, Map.of("Payment-Signature", signed)).expect(200);
        hooks.post("/webhooks/payments", body, Map.of("Payment-Signature", signed)).expect(200);

        assertThat(invoice(w, "PRORATION").path("status").asText()).isEqualTo("PAID");
        assertThat(owner(w).get("/v1/billing").expect(200).text("status")).isEqualTo("ACTIVE");
    }
}
