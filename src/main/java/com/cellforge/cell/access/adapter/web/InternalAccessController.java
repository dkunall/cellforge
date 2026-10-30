package com.cellforge.cell.access.adapter.web;

import com.cellforge.cell.access.application.AccessService;
import com.cellforge.cell.tenancy.application.TenantContext;
import com.cellforge.cell.tenancy.application.TenantContext.Scope;
import com.cellforge.shared.error.NotFoundException;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the edge needs to bill a tenant and to authorise its billing pages. */
@RestController
@RequestMapping("/internal/tenants/{tenantId}")
class InternalAccessController {

    private final AccessService access;

    InternalAccessController(AccessService access) {
        this.access = access;
    }

    @GetMapping("/seats")
    Map<String, Long> seats(@PathVariable UUID tenantId) {
        return Map.of("seats", TenantContext.call(Scope.internal(tenantId), access::seatsInUse));
    }

    @GetMapping("/members/{userId}")
    Map<String, String> member(@PathVariable UUID tenantId, @PathVariable String userId) {
        return TenantContext.call(Scope.internal(tenantId), () -> access.roleOf(userId))
            .map(role -> Map.of("userId", userId, "role", role.name()))
            .orElseThrow(() -> new NotFoundException("member-not-found", "No member " + userId));
    }
}
