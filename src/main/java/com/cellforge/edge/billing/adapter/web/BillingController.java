package com.cellforge.edge.billing.adapter.web;

import com.cellforge.edge.billing.application.Billing;
import com.cellforge.edge.billing.domain.Invoice;
import com.cellforge.edge.directory.application.Directory;
import com.cellforge.edge.directory.domain.TenantRoute;
import com.cellforge.shared.error.InvalidRequestException;
import com.cellforge.shared.error.NotFoundException;
import com.cellforge.shared.error.PreconditionRequiredException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Billing lives in the edge, so these requests are answered here rather than forwarded to a cell. */
@RestController
@RequestMapping("/v1/billing")
class BillingController {

    record PaymentMethod(@NotBlank @Size(max = 64) String token) {
    }

    record PlanRequest(@NotBlank @Size(max = 32) String plan) {
    }

    record OverviewView(String plan, String status, Instant trialEndsAt, Instant periodStart, Instant periodEnd,
                        Instant graceUntil, String cardLast4, long creditCents, long seats, int seatPriceCents,
                        long callsThisPeriod) {
    }

    private final Billing billing;
    private final Directory directory;

    BillingController(Billing billing, Directory directory) {
        this.billing = billing;
        this.directory = directory;
    }

    @GetMapping
    OverviewView overview(@RequestHeader(name = "X-Tenant", required = false) String slug, @AuthenticationPrincipal Jwt jwt) {
        TenantRoute tenant = admin(slug, jwt);
        Billing.Overview o = billing.overview(tenant);
        var s = o.subscription();
        return new OverviewView(s.planCode(), s.status().name(), s.trialEndsAt(), s.periodStart(), s.periodEnd(),
            s.graceUntil(), s.cardLast4(), s.creditCents(), o.seats(), o.plan().seatPriceCents(), o.callsThisPeriod());
    }

    @GetMapping("/invoices")
    List<Invoice> invoices(@RequestHeader(name = "X-Tenant", required = false) String slug, @AuthenticationPrincipal Jwt jwt) {
        return billing.invoices(admin(slug, jwt).id());
    }

    @PutMapping("/payment-method")
    Map<String, String> paymentMethod(@RequestHeader(name = "X-Tenant", required = false) String slug, @AuthenticationPrincipal Jwt jwt,
                         @Valid @RequestBody PaymentMethod body) {
        return Map.of("cardLast4", billing.setPaymentMethod(admin(slug, jwt).id(), body.token()));
    }

    @PostMapping("/plan")
    ResponseEntity<Billing.PlanChange> changePlan(@RequestHeader(name = "X-Tenant", required = false) String slug,
                                                  @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                  @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PlanRequest body) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new PreconditionRequiredException("idempotency-key-required",
                "Plan changes move money, so they need an Idempotency-Key header");
        }
        TenantRoute tenant = admin(slug, jwt);
        // The body has one field, so the plan itself is a complete fingerprint of the request.
        Billing.PlanChange change = billing.changePlan(tenant, body.plan().trim().toUpperCase(), key, body.plan().trim().toUpperCase());
        return ResponseEntity.ok().header("Idempotent-Replayed", String.valueOf(change.replayed())).body(change);
    }

    private TenantRoute admin(String slug, Jwt jwt) {
        if (slug == null || slug.isBlank()) {
            throw new InvalidRequestException("tenant-required", "Missing X-Tenant header");
        }
        TenantRoute tenant = directory.bySlug(slug.trim())
            .orElseThrow(() -> new NotFoundException("tenant-not-found", "No workspace " + slug));
        billing.requireBillingAdmin(tenant, jwt.getSubject());
        return tenant;
    }
}
