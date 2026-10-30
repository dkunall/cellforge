package com.cellforge.cell.tenancy.adapter.persistence;

import com.cellforge.cell.tenancy.application.TenantContext;
import com.cellforge.cell.tenancy.application.TenantContext.Mode;
import com.cellforge.cell.tenancy.application.TenantContext.Scope;
import com.cellforge.shared.error.NotHostedException;
import com.cellforge.shared.error.UnavailableException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Scopes every transaction to the tenant on the thread before any of its statements run. The settings are
 * transaction-local (set_config(..., true) is SET LOCAL), so a pooled connection carries nothing into the next
 * borrower. With no tenant on the thread nothing is set and RLS returns no rows: forgetting fails closed.
 *
 * <p>Member writes also take a share lock on the tenant row. Freezing a tenant for a move updates that row, so
 * the freeze waits for writes already running and every later write sees FROZEN: no write can slip in after
 * the export has started.
 */
public class RlsTransactionManager extends JdbcTransactionManager {

    public RlsTransactionManager(DataSource dataSource) {
        super(dataSource);
        setEnforceReadOnly(true);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition) throws SQLException {
        super.prepareTransactionalConnection(con, definition);
        Scope scope = TenantContext.current().orElse(null);
        if (scope == null) {
            return;
        }
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, true), set_config('statement_timeout', ?, true)")) {
            ps.setString(1, scope.tenantId().toString());
            ps.setString(2, scope.statementTimeout().toMillis() + "ms");
            ps.execute();
        }
        if (scope.mode() == Mode.MEMBER && !definition.isReadOnly()) {
            guardWrite(con);
        }
    }

    private static void guardWrite(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT status FROM tenants WHERE id = current_tenant() FOR SHARE");
             ResultSet rs = ps.executeQuery()) {
            String status = rs.next() ? rs.getString(1) : "MOVED";
            switch (status) {
                case "ACTIVE" -> { }
                case "FROZEN" -> throw new UnavailableException("tenant-moving",
                    "This workspace is being moved to another cell; writes resume in a few seconds", 2);
                default -> throw new NotHostedException("Tenant is not hosted on this cell");
            }
        }
    }
}
