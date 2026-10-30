package com.cellforge.cell.tenancy.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cellforge.cell.tenancy.application.TenantContext;
import com.cellforge.cell.tenancy.application.TenantContext.Mode;
import com.cellforge.cell.tenancy.application.TenantContext.Scope;
import com.cellforge.shared.error.UnavailableException;
import com.cellforge.support.Containers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the real migrations and connects as the real runtime role, then tries to read and write across tenants
 * the ways a bug in application code would.
 */
class RowLevelSecurityIT {

    private static HikariDataSource app;
    private static JdbcClient jdbc;
    private static TransactionTemplate tx;
    private static TransactionTemplate readOnly;
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @BeforeAll
    static void migrate() throws Exception {
        String db = "rls_" + Long.toHexString(System.nanoTime());
        try (Connection c = DriverManager.getConnection(Containers.jdbcUrl("directory"), Containers.OWNER,
                Containers.OWNER_PASSWORD); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + db);
        }
        String url = Containers.jdbcUrl(db);
        Flyway.configure().dataSource(url, Containers.OWNER, Containers.OWNER_PASSWORD)
            .locations("classpath:db/cell")
            .placeholders(Map.of("appUser", Containers.APP_USER, "appPassword", Containers.APP_PASSWORD))
            .load().migrate();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(Containers.APP_USER);
        config.setPassword(Containers.APP_PASSWORD);
        // One connection, so the "nothing leaks to the next borrower" check really reuses it.
        config.setMaximumPoolSize(1);
        app = new HikariDataSource(config);
        jdbc = JdbcClient.create(app);
        RlsTransactionManager manager = new RlsTransactionManager(app);
        tx = new TransactionTemplate(manager);
        readOnly = new TransactionTemplate(manager);
        readOnly.setReadOnly(true);
        provision(A, "acme");
        provision(B, "globex");
    }

    @AfterAll
    static void close() {
        app.close();
    }

    private static void provision(UUID tenant, String slug) {
        TenantContext.run(Scope.internal(tenant), () -> tx.executeWithoutResult(s -> {
            jdbc.sql("INSERT INTO tenants (id, slug, name, status, created_at) VALUES (?, ?, ?, 'ACTIVE', now())")
                .params(tenant, slug, slug).update();
            jdbc.sql("INSERT INTO memberships (tenant_id, user_id, display_name, role, joined_at) VALUES (?, ?, ?, 'OWNER', now())")
                .params(tenant, slug + "-owner", slug).update();
        }));
    }

    private static Scope member(UUID tenant) {
        return new Scope(tenant, Mode.MEMBER, null, Duration.ofSeconds(5));
    }

    private static long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    void runtimeRoleCannotBypassRls() {
        Map<String, Object> role = jdbc.sql("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")
            .query().singleRow();
        assertThat(role).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false);
        long owned = jdbc.sql("SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner = current_user")
            .query(Long.class).single();
        assertThat(owned).isZero();
    }

    @Test
    void everyTenantTableHasForcedRls() {
        List<String> unprotected = jdbc.sql("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
                  AND NOT (c.relrowsecurity AND c.relforcerowsecurity)
                """).query(String.class).list();
        assertThat(unprotected).isEmpty();
    }

    @Test
    void noTenantInScopeSeesNothing() {
        assertThat(count("tenants")).isZero();
        Long rows = readOnly.execute(s -> count("memberships"));
        assertThat(rows).isZero();
    }

    @Test
    void aTenantSeesOnlyItsOwnRows() {
        List<String> slugs = TenantContext.call(member(A), () -> readOnly.execute(s ->
            jdbc.sql("SELECT slug FROM tenants").query(String.class).list()));
        assertThat(slugs).containsExactly("acme");
        List<String> users = TenantContext.call(member(A), () -> readOnly.execute(s ->
            jdbc.sql("SELECT user_id FROM memberships WHERE tenant_id = ?").param(B).query(String.class).list()));
        assertThat(users).as("asking for B by id from A's scope").isEmpty();
    }

    @Test
    void crossTenantWritesAreRejectedOrMissNothing() {
        assertThatThrownBy(() -> TenantContext.run(member(A), () -> tx.executeWithoutResult(s ->
            jdbc.sql("INSERT INTO memberships (tenant_id, user_id, display_name, role, joined_at) VALUES (?, 'mallory', 'm', 'ADMIN', now())")
                .param(B).update())))
            .isInstanceOf(DataAccessException.class)
            .rootCause().hasMessageContaining("row-level security");
        int touched = TenantContext.call(member(A), () -> tx.execute(s ->
            jdbc.sql("UPDATE memberships SET role = 'GUEST' WHERE tenant_id = ?").param(B).update()
                + jdbc.sql("DELETE FROM memberships WHERE tenant_id = ?").param(B).update()));
        assertThat(touched).isZero();
        String role = TenantContext.call(Scope.internal(B), () -> readOnly.execute(s ->
            jdbc.sql("SELECT role FROM memberships WHERE user_id = 'globex-owner'").query(String.class).single()));
        assertThat(role).isEqualTo("OWNER");
    }

    @Test
    void tenantSettingDoesNotOutliveTheTransaction() {
        TenantContext.run(member(A), () -> readOnly.executeWithoutResult(s -> assertThat(count("tenants")).isOne()));
        // Same single pooled connection, now outside any transaction and scope.
        assertThat(count("tenants")).isZero();
        assertThat(jdbc.sql("SELECT current_tenant()").query(UUID.class).optional()).isEmpty();
    }

    @Test
    void frozenTenantRefusesMemberWritesButServesReads() {
        UUID frozen = UUID.randomUUID();
        provision(frozen, "initech");
        TenantContext.run(Scope.internal(frozen), () -> tx.executeWithoutResult(s ->
            jdbc.sql("UPDATE tenants SET status = 'FROZEN'").update()));

        assertThatThrownBy(() -> TenantContext.run(member(frozen), () -> tx.executeWithoutResult(s -> { })))
            .isInstanceOf(CannotCreateTransactionException.class)
            .cause().isInstanceOf(UnavailableException.class);
        long rows = TenantContext.call(member(frozen), () -> readOnly.execute(s -> count("memberships")));
        assertThat(rows).isOne();
    }

    @Test
    void freezeWaitsForWritesAlreadyRunning() throws Exception {
        UUID busy = UUID.randomUUID();
        provision(busy, "hooli");
        // Separate pools: the single-connection pool would serialise the two transactions on its own.
        try (HikariDataSource second = new HikariDataSource(copyOf(app))) {
            TransactionTemplate writer = new TransactionTemplate(new RlsTransactionManager(second));
            JdbcClient writerJdbc = JdbcClient.create(second);
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Void> write = CompletableFuture.runAsync(() -> TenantContext.run(member(busy), () ->
                writer.executeWithoutResult(s -> {
                    inside.countDown();
                    await(release);
                    writerJdbc.sql("UPDATE memberships SET display_name = 'late write'").update();
                })));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> freeze = CompletableFuture.runAsync(() -> TenantContext.run(Scope.internal(busy), () ->
                tx.executeWithoutResult(s -> jdbc.sql("UPDATE tenants SET status = 'FROZEN'").update())));
            Thread.sleep(300);
            assertThat(freeze).as("freeze must wait for the in-flight write").isNotDone();

            release.countDown();
            write.get(5, TimeUnit.SECONDS);
            freeze.get(5, TimeUnit.SECONDS);
            String name = TenantContext.call(Scope.internal(busy), () -> readOnly.execute(s ->
                jdbc.sql("SELECT display_name FROM memberships").query(String.class).single()));
            assertThat(name).isEqualTo("late write");
        }
    }

    private static HikariConfig copyOf(HikariDataSource source) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(source.getJdbcUrl());
        config.setUsername(source.getUsername());
        config.setPassword(source.getPassword());
        config.setMaximumPoolSize(1);
        return config;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
