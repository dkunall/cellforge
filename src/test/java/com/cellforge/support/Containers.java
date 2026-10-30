package com.cellforge.support;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * One Postgres, one Kafka and one Redis per JVM, started on first use. Postgres holds three databases so the
 * edge's directory and the two cells stay as separate as they are in compose. Passwords are random per run.
 */
public final class Containers {

    public static final String OWNER = "cellforge";
    public static final String APP_USER = "cellforge_app";
    public static final String OWNER_PASSWORD = random();
    public static final String APP_PASSWORD = random();

    private static PostgreSQLContainer<?> postgres;
    private static KafkaContainer kafka;
    private static GenericContainer<?> redis;

    private Containers() {
    }

    public static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("directory").withUsername(OWNER).withPassword(OWNER_PASSWORD);
            pg.start();
            try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), OWNER, OWNER_PASSWORD);
                 Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE cell_a");
                s.execute("CREATE DATABASE cell_b");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            postgres = pg;
        }
        return postgres;
    }

    public static String jdbcUrl(String database) {
        PostgreSQLContainer<?> pg = postgres();
        return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + database;
    }

    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            KafkaContainer k = new KafkaContainer("apache/kafka:3.8.0").withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");
            k.start();
            kafka = k;
        }
        return kafka;
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            GenericContainer<?> r = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
            r.start();
            redis = r;
        }
        return redis;
    }

    private static String random() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
