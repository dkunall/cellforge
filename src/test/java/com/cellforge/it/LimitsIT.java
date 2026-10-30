package com.cellforge.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.cellforge.cell.tenancy.application.TenantContext;
import com.cellforge.cell.tenancy.application.TenantContext.Mode;
import com.cellforge.cell.tenancy.application.TenantContext.Scope;
import com.cellforge.edge.billing.application.Accounts;
import com.cellforge.support.Api;
import com.cellforge.support.Platform;
import com.cellforge.support.Platform.Workspace;
import com.cellforge.support.TestJwt;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LimitsIT {

    private final Platform platform = Platform.get();

    private Api as(Workspace w) {
        return platform.edge().as(TestJwt.user(w.ownerId())).in(w.slug());
    }

    @Test
    void aBurstPastTheBucketIsRefusedWithoutSlowingOtherTenants() {
        Workspace noisy = platform.workspace("noisy", "FREE");
        Workspace quiet = platform.workspace("quiet", "FREE");

        List<Api.Response> burst = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            burst.add(as(noisy).get("/v1/projects"));
        }

        List<Api.Response> refused = burst.stream().filter(r -> r.status() == 429).toList();
        assertThat(refused).isNotEmpty().allSatisfy(r -> {
            assertThat(r.text("code")).isEqualTo("rate-limited");
            assertThat(Integer.parseInt(r.header("Retry-After"))).isPositive();
        });
        as(quiet).get("/v1/projects").expect(200);
    }

    @Test
    void anExhaustedMonthlyQuotaIsRefusedUntilTheNextMonth() {
        Workspace w = platform.workspace("quota", "FREE");
        String month = YearMonth.now(platform.clock().withZone(ZoneOffset.UTC)).toString();
        // FREE allows 20000 calls a month; pretend they are all used.
        platform.edgeBean(StringRedisTemplate.class).opsForValue().set("quota:{" + w.id() + "}:" + month, "20000");

        Api.Response refused = as(w).get("/v1/projects").expect(429);

        assertThat(refused.text("code")).isEqualTo("quota-exhausted");
        assertThat(refused.header("Retry-After")).isEqualTo("3600");
    }

    @Test
    void aPlanChangeReachesTheCellWellBeforeItsCacheExpires() {
        Workspace w = platform.workspace("upgrader", "FREE");
        Api api = as(w);
        api.post("/v1/projects", Map.of("key", "ONE", "name", "One")).expect(201);
        api.post("/v1/projects", Map.of("key", "TWO", "name", "Two")).expect(201);
        assertThat(api.post("/v1/projects", Map.of("key", "TRE", "name", "Three")).expect(422).text("code"))
            .isEqualTo("project-limit");

        platform.directoryDb().sql("UPDATE subscriptions SET plan_code = 'PRO' WHERE tenant_id = ?")
            .param(w.id()).update();
        Accounts accounts = platform.edgeBean(Accounts.class);

        // Re-announcing covers a listener that joined its group after the first event; the cell cache lives
        // 60s, so anything inside 20s came from the event and not from expiry.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500)).until(() -> {
            accounts.changed(w.id());
            return api.post("/v1/projects", Map.of("key", "TRE", "name", "Three")).status() == 201;
        });
    }

    @Test
    void aQueryOverThePlanBudgetIsCancelledByPostgres() {
        Workspace w = platform.workspace("slowpoke", "FREE");
        TransactionTemplate tx = new TransactionTemplate(platform.cellBean(w.cellId(), PlatformTransactionManager.class));
        JdbcClient db = JdbcClient.create(platform.cellBean(w.cellId(), DataSource.class));
        Scope scope = new Scope(w.id(), Mode.INTERNAL, null, Duration.ofMillis(200));

        assertThatThrownBy(() -> TenantContext.run(scope, () ->
            tx.executeWithoutResult(s -> db.sql("SELECT pg_sleep(2)").query().singleValue())))
            .isInstanceOf(QueryTimeoutException.class);

        TenantContext.run(scope, () ->
            tx.executeWithoutResult(s -> db.sql("SELECT pg_sleep(0.05)").query().singleValue()));
    }
}
