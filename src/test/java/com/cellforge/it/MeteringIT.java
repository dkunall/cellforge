package com.cellforge.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.cellforge.support.Api;
import com.cellforge.support.Platform;
import com.cellforge.support.Platform.Workspace;
import com.cellforge.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class MeteringIT {

    private final Platform platform = Platform.get();

    private long calls(UUID tenantId) {
        return platform.directoryDb().sql("SELECT coalesce(sum(calls), 0) FROM usage_hourly WHERE tenant_id = ?")
            .param(tenantId).query(Long.class).single();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyForwardedCallIsCountedOnceEvenWhenKafkaRedelivers() throws Exception {
        Workspace w = platform.workspace("metered", "FREE");
        Api api = platform.edge().as(TestJwt.user(w.ownerId())).in(w.slug());
        for (int i = 0; i < 5; i++) {
            api.get("/v1/projects").expect(200);
        }
        api.get("/v1/projects/" + UUID.randomUUID()).expect(404);
        await().atMost(Duration.ofSeconds(20)).until(() -> calls(w.id()) == 6);

        // Replay what was already consumed, as a consumer restart before its offset commit would.
        List<String> seen = platform.directoryDb()
            .sql("SELECT event_id::text FROM usage_events WHERE tenant_id = ?").param(w.id()).query(String.class).list();
        KafkaTemplate<String, String> kafka = platform.edgeBean(KafkaTemplate.class);
        for (String id : seen) {
            kafka.send("cellforge.usage", w.id().toString(), event(id, w.id(), 200)).get();
        }
        // One new event after the replays; the topic is keyed by tenant, so once it lands the replays have too.
        kafka.send("cellforge.usage", w.id().toString(), "{not json").get();
        kafka.send("cellforge.usage", w.id().toString(), event(UUID.randomUUID().toString(), w.id(), 503)).get();

        await().atMost(Duration.ofSeconds(20)).until(() -> calls(w.id()) >= 7);
        assertThat(calls(w.id())).isEqualTo(7);
        assertThat(platform.directoryDb().sql("SELECT sum(errors) FROM usage_hourly WHERE tenant_id = ?")
            .param(w.id()).query(Long.class).single()).isEqualTo(1);
    }

    private static String event(String id, UUID tenant, int status) {
        return """
            {"eventId":"%s","tenantId":"%s","at":"%s","method":"GET","status":%d}"""
            .formatted(id, tenant, Instant.now(), status);
    }
}
