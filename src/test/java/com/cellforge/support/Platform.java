package com.cellforge.support;

import com.cellforge.cell.CellApplication;
import com.cellforge.edge.EdgeApplication;
import com.cellforge.shared.security.ServiceTokens;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * The whole system in one JVM: an edge and two cells, each its own Spring context on its own port, sharing
 * the containers from {@link Containers}. Started once and shared by every integration test; tests isolate
 * themselves by creating their own workspaces.
 */
public final class Platform {

    public static final String CELL_A = "cell-a";
    public static final String CELL_B = "cell-b";

    private static Platform instance;

    private final int edgePort;
    private final Map<String, ConfigurableApplicationContext> cells = new LinkedHashMap<>();
    private final ConfigurableApplicationContext edge;
    private final MutableClock clock = new MutableClock();

    private Platform() {
        int edgeFree = freePort();
        int aPort = freePort();
        int bPort = freePort();
        this.edgePort = edgeFree;
        String edgeUrl = "http://localhost:" + edgeFree;
        // The edge serves the cells' entitlements, but a cell only asks on its first tenant request, so the
        // cells can start before the edge as long as they know where it will be.
        cells.put(CELL_A, startCell(CELL_A, "cell_a", aPort, edgeUrl));
        cells.put(CELL_B, startCell(CELL_B, "cell_b", bPort, edgeUrl));
        this.edge = start(new SpringApplicationBuilder(EdgeApplication.class).properties("spring.config.name=edge"),
            "cellforge-edge", Map.of(
                "PORT", Integer.toString(edgeFree),
                "spring.datasource.url", Containers.jdbcUrl("directory"),
                "spring.datasource.username", Containers.OWNER,
                "spring.datasource.password", Containers.OWNER_PASSWORD,
                "spring.data.redis.host", Containers.redis().getHost(),
                "spring.data.redis.port", Integer.toString(Containers.redis().getMappedPort(6379)),
                "cellforge.cells.cell-a", "http://localhost:" + aPort,
                "cellforge.cells.cell-b", "http://localhost:" + bPort));
    }

    public static synchronized Platform get() {
        if (instance == null) {
            instance = new Platform();
            Runtime.getRuntime().addShutdownHook(new Thread(instance::stop));
        }
        return instance;
    }

    public Api edge() {
        return Api.at(edgePort);
    }

    public int edgePort() {
        return edgePort;
    }

    public MutableClock clock() {
        return clock;
    }

    public <T> T edgeBean(Class<T> type) {
        return edge.getBean(type);
    }

    public <T> T cellBean(String cellId, Class<T> type) {
        return cells.get(cellId).getBean(type);
    }

    /** Owner connection to the edge's directory database, for assertions that look behind the API. */
    public JdbcClient directoryDb() {
        return JdbcClient.create(edge.getBean(DataSource.class));
    }

    /** Creates a workspace through the public API and returns its slug. */
    public Workspace workspace(String ownerId, String plan) {
        String slug = "w" + Long.toHexString(System.nanoTime()) + Integer.toHexString((int) (Math.random() * 0xfff));
        Api.Response created = edge().as(TestJwt.user(ownerId))
            .post("/v1/tenants", Map.of("slug", slug, "name", "Workspace " + slug, "plan", plan)).expect(201);
        String cell = directoryDb().sql("SELECT cell_id FROM tenants WHERE slug = ?").param(slug)
            .query(String.class).single();
        return new Workspace(UUID.fromString(created.text("id")), slug, cell, ownerId);
    }

    public record Workspace(UUID id, String slug, String cellId, String ownerId) {
    }

    private ConfigurableApplicationContext startCell(String id, String database, int port, String edgeUrl) {
        return start(new SpringApplicationBuilder(CellApplication.class).properties("spring.config.name=cell"),
            "cellforge-" + id, Map.of(
                "PORT", Integer.toString(port),
                "CELL_ID", id,
                "spring.datasource.url", Containers.jdbcUrl(database),
                "spring.datasource.password", Containers.APP_PASSWORD,
                "spring.flyway.user", Containers.OWNER,
                "spring.flyway.password", Containers.OWNER_PASSWORD,
                "cellforge.edge.url", edgeUrl));
    }

    private ConfigurableApplicationContext start(SpringApplicationBuilder builder, String client,
                                                 Map<String, String> specific) {
        Map<String, Object> properties = new LinkedHashMap<>(specific);
        properties.put("spring.kafka.bootstrap-servers", Containers.kafka().getBootstrapServers());
        properties.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://test-issuer/cellforge");
        properties.put("cellforge.service-token.client-secret", "unused");
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "WARN");
        String serviceToken = TestJwt.service(client, "cellforge.internal");
        return builder
            .initializers(ctx -> {
                // Highest precedence: builder default properties would lose to the ${ENV:default} values in the yml.
                ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("platform", properties));
                GenericApplicationContext generic = (GenericApplicationContext) ctx;
                generic.registerBean(JwtDecoder.class, TestJwt::decoder, bd -> bd.setPrimary(true));
                generic.registerBean(ServiceTokens.class, () -> () -> serviceToken);
                generic.registerBean(Clock.class, () -> clock, bd -> bd.setPrimary(true));
            })
            .run();
    }

    private void stop() {
        edge.close();
        cells.values().forEach(ConfigurableApplicationContext::close);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
