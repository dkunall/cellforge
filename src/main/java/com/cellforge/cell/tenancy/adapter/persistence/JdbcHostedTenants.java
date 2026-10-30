package com.cellforge.cell.tenancy.adapter.persistence;

import com.cellforge.cell.tenancy.application.HostedTenants;
import com.cellforge.cell.tenancy.domain.HostedTenant;
import com.cellforge.cell.tenancy.domain.TenantStatus;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcHostedTenants implements HostedTenants {

    private final JdbcClient jdbc;

    JdbcHostedTenants(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<HostedTenant> current() {
        return jdbc.sql("SELECT id, slug, name, status, created_at FROM tenants WHERE id = current_tenant()")
            .query((rs, n) -> new HostedTenant(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                TenantStatus.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant()))
            .optional();
    }

    @Override
    public void insert(HostedTenant tenant) {
        jdbc.sql("INSERT INTO tenants (id, slug, name, status, created_at) VALUES (?, ?, ?, ?, ?)")
            .params(tenant.id(), tenant.slug(), tenant.name(), tenant.status().name(), Timestamp.from(tenant.createdAt()))
            .update();
    }

    @Override
    public void setStatus(TenantStatus status) {
        jdbc.sql("UPDATE tenants SET status = ? WHERE id = current_tenant()").param(status.name()).update();
    }

    @Override
    public void delete() {
        jdbc.sql("DELETE FROM tenants WHERE id = current_tenant()").update();
    }
}
