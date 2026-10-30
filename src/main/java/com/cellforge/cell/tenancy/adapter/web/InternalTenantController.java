package com.cellforge.cell.tenancy.adapter.web;

import com.cellforge.cell.tenancy.application.Provisioning;
import com.cellforge.cell.tenancy.application.TenantContext;
import com.cellforge.cell.tenancy.application.TenantContext.Scope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Control-plane calls from the edge. Path ids are trusted here because only the edge holds the internal scope. */
@RestController
@RequestMapping("/internal/tenants")
class InternalTenantController {

    record OwnerBody(@NotBlank String userId, String email, @NotBlank String displayName) {
    }

    record ProvisionBody(@NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,38}[a-z0-9]") String slug,
                         @NotBlank String name, @NotNull @Valid OwnerBody owner) {
    }

    private final Provisioning provisioning;

    InternalTenantController(Provisioning provisioning) {
        this.provisioning = provisioning;
    }

    /** PUT because the edge picks the id: repeating the call converges on the same tenant. */
    @PutMapping("/{tenantId}")
    ResponseEntity<Map<String, Object>> provision(@PathVariable UUID tenantId, @Valid @RequestBody ProvisionBody body) {
        boolean created = TenantContext.call(Scope.internal(tenantId), () -> provisioning.provision(body.slug(), body.name(),
            new Provisioning.Owner(body.owner().userId(), body.owner().email(), body.owner().displayName())));
        return ResponseEntity.status(created ? 201 : 200).body(Map.of("tenantId", tenantId, "created", created));
    }
}
