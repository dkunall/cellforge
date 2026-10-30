package com.cellforge.edge.directory.adapter.web;

import com.cellforge.edge.directory.application.CellClient;
import com.cellforge.edge.directory.application.TenantProvisioning;
import com.cellforge.edge.directory.domain.TenantRoute;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/tenants")
class TenantController {

    record NewTenant(@NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,38}[a-z0-9]") String slug,
                     @NotBlank @Size(max = 120) String name, @Pattern(regexp = "FREE|PRO|ENTERPRISE") String plan) {
    }

    record TenantView(UUID id, String slug, String name, String status, Instant createdAt) {
        static TenantView of(TenantRoute r) {
            return new TenantView(r.id(), r.slug(), r.name(), r.status().name(), r.createdAt());
        }
    }

    private final TenantProvisioning provisioning;

    TenantController(TenantProvisioning provisioning) {
        this.provisioning = provisioning;
    }

    /** Repeating the same request returns the existing workspace (200) instead of failing. */
    @PostMapping
    ResponseEntity<TenantView> create(@Valid @RequestBody NewTenant body, @AuthenticationPrincipal Jwt jwt) {
        String display = jwt.getClaimAsString("name") != null ? jwt.getClaimAsString("name") : jwt.getSubject();
        TenantProvisioning.Result result = provisioning.create(body.slug(), body.name().trim(),
            body.plan() == null ? "FREE" : body.plan(),
            new CellClient.Owner(jwt.getSubject(), jwt.getClaimAsString("email"), display));
        return ResponseEntity.status(result.created() ? 201 : 200).body(TenantView.of(result.tenant()));
    }
}
