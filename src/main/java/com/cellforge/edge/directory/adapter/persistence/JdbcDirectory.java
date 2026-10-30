package com.cellforge.edge.directory.adapter.persistence;

import com.cellforge.edge.directory.application.Directory;
import com.cellforge.edge.directory.domain.CellLoad;
import com.cellforge.edge.directory.domain.RouteStatus;
import com.cellforge.edge.directory.domain.TenantRoute;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDirectory implements Directory {

    private static final String ROUTE = "SELECT t.id, t.slug, t.name, t.cell_id, c.internal_url, t.status, "
        + "t.owner_user_id, t.created_at FROM tenants t JOIN cells c ON c.id = t.cell_id ";

    private final JdbcClient jdbc;

    JdbcDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void registerCell(String id, String internalUrl) {
        jdbc.sql("INSERT INTO cells (id, internal_url) VALUES (?, ?) "
                + "ON CONFLICT (id) DO UPDATE SET internal_url = excluded.internal_url")
            .params(id, internalUrl).update();
    }

    @Override
    public List<CellLoad> loads() {
        return jdbc.sql("SELECT c.id, c.internal_url, c.accepting, count(t.id) AS tenants FROM cells c "
                + "LEFT JOIN tenants t ON t.cell_id = c.id GROUP BY c.id ORDER BY c.id")
            .query((rs, n) -> new CellLoad(rs.getString("id"), rs.getString("internal_url"), rs.getBoolean("accepting"),
                rs.getLong("tenants")))
            .list();
    }

    @Override
    public Optional<String> cellUrl(String cellId) {
        return jdbc.sql("SELECT internal_url FROM cells WHERE id = ?").param(cellId).query(String.class).optional();
    }

    @Override
    public boolean insert(UUID id, String slug, String name, String cellId, String ownerUserId) {
        return jdbc.sql("INSERT INTO tenants (id, slug, name, cell_id, status, owner_user_id, created_at) "
                + "VALUES (?, ?, ?, ?, 'PROVISIONING', ?, now()) ON CONFLICT (slug) DO NOTHING")
            .params(id, slug, name, cellId, ownerUserId).update() == 1;
    }

    @Override
    public Optional<TenantRoute> bySlug(String slug) {
        return jdbc.sql(ROUTE + "WHERE t.slug = ?").param(slug).query(JdbcDirectory::map).optional();
    }

    @Override
    public Optional<TenantRoute> byId(UUID id) {
        return jdbc.sql(ROUTE + "WHERE t.id = ?").param(id).query(JdbcDirectory::map).optional();
    }

    @Override
    public void setStatus(UUID id, RouteStatus status) {
        jdbc.sql("UPDATE tenants SET status = ? WHERE id = ?").params(status.name(), id).update();
    }

    @Override
    public boolean beginMove(UUID id) {
        return jdbc.sql("UPDATE tenants SET status = 'MOVING' WHERE id = ? AND status = 'ACTIVE'").param(id).update() == 1;
    }

    @Override
    public void moveTo(UUID id, String cellId) {
        jdbc.sql("UPDATE tenants SET cell_id = ?, status = 'ACTIVE' WHERE id = ?").params(cellId, id).update();
    }

    private static TenantRoute map(ResultSet rs, int n) throws SQLException {
        return new TenantRoute(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
            rs.getString("cell_id"), rs.getString("internal_url"), RouteStatus.valueOf(rs.getString("status")),
            rs.getString("owner_user_id"), rs.getTimestamp("created_at").toInstant());
    }
}
