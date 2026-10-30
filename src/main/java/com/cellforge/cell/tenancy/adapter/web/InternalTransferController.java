package com.cellforge.cell.tenancy.adapter.web;

import com.cellforge.cell.tenancy.application.TenantContext;
import com.cellforge.cell.tenancy.application.TenantContext.Scope;
import com.cellforge.cell.tenancy.application.TenantSnapshots;
import com.cellforge.cell.tenancy.application.TenantTransfer;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/tenants/{tenantId}/transfer")
class InternalTransferController {

    record Restore(Map<String, String> tables) {
    }

    private final TenantTransfer transfer;

    InternalTransferController(TenantTransfer transfer) {
        this.transfer = transfer;
    }

    @PostMapping("/freeze")
    ResponseEntity<Void> freeze(@PathVariable UUID tenantId) {
        return run(tenantId, transfer::freeze);
    }

    @PostMapping("/unfreeze")
    ResponseEntity<Void> unfreeze(@PathVariable UUID tenantId) {
        return run(tenantId, transfer::unfreeze);
    }

    @GetMapping
    TenantTransfer.Snapshot export(@PathVariable UUID tenantId) {
        return TenantContext.call(Scope.internal(tenantId), transfer::export);
    }

    @PutMapping
    Map<String, TenantSnapshots.TableDigest> restore(@PathVariable UUID tenantId, @RequestBody Restore body) {
        return TenantContext.call(Scope.internal(tenantId), () -> transfer.restore(body.tables()));
    }

    @PostMapping("/activate")
    ResponseEntity<Void> activate(@PathVariable UUID tenantId) {
        return run(tenantId, transfer::activate);
    }

    @PostMapping("/moved")
    ResponseEntity<Void> moved(@PathVariable UUID tenantId) {
        return run(tenantId, transfer::markMoved);
    }

    @DeleteMapping
    ResponseEntity<Void> purge(@PathVariable UUID tenantId) {
        return run(tenantId, transfer::purge);
    }

    private static ResponseEntity<Void> run(UUID tenantId, Runnable step) {
        TenantContext.run(Scope.internal(tenantId), step);
        return ResponseEntity.noContent().build();
    }
}
