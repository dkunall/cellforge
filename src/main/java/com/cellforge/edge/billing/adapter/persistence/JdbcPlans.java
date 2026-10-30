package com.cellforge.edge.billing.adapter.persistence;

import com.cellforge.edge.billing.application.Plans;
import com.cellforge.edge.billing.domain.Plan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPlans implements Plans {

    private static final TypeReference<List<Plan.ApiTier>> TIERS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcPlans(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<Plan> find(String code) {
        return jdbc.sql("SELECT * FROM plans WHERE code = ?").param(code).query(this::map).optional();
    }

    private Plan map(ResultSet rs, int n) throws SQLException {
        try {
            return new Plan(rs.getString("code"), rs.getString("name"), rs.getInt("seat_price_cents"),
                rs.getInt("trial_days"), rs.getInt("max_seats"), rs.getInt("max_projects"),
                rs.getInt("requests_per_minute"), rs.getInt("burst"), rs.getLong("monthly_api_quota"),
                Duration.ofMillis(rs.getInt("statement_timeout_ms")), rs.getInt("max_concurrent"),
                Set.of((String[]) rs.getArray("features").getArray()), json.readValue(rs.getString("api_tiers"), TIERS));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Bad api_tiers for plan " + rs.getString("code"), e);
        }
    }
}
