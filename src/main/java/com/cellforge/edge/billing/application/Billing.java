package com.cellforge.edge.billing.application;

import com.cellforge.edge.billing.domain.Invoice;
import com.cellforge.edge.billing.domain.Plan;
import com.cellforge.edge.billing.domain.Pricing;
import com.cellforge.edge.billing.domain.Subscription;
import com.cellforge.edge.billing.domain.SubscriptionStatus;
import com.cellforge.edge.directory.application.CellClient;
import com.cellforge.edge.directory.application.Directory;
import com.cellforge.edge.directory.domain.TenantRoute;
import com.cellforge.edge.metering.application.Metering;
import com.cellforge.shared.error.BusinessRuleException;
import com.cellforge.shared.error.ForbiddenException;
import com.cellforge.shared.error.InvalidRequestException;
import com.cellforge.shared.error.NotFoundException;
import com.cellforge.shared.error.PaymentRequiredException;
import com.cellforge.shared.error.StateConflictException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Calls to the cell (seat counts) and to the payment provider happen outside transactions, so a slow cell
 * or processor never holds a subscription row lock. Every write re-reads the row under lock and re-checks
 * what it depends on.
 */
@Service
public class Billing {

    public static final Duration GRACE = Duration.ofDays(7);
    private static final Set<String> BILLING_ROLES = Set.of("OWNER", "ADMIN");
    private static final Logger log = LoggerFactory.getLogger(Billing.class);

    public record Overview(Subscription subscription, Plan plan, long callsThisPeriod, long seats) {
    }

    public record PlanChange(String fromPlan, String toPlan, long proratedCents, Invoice invoice, boolean replayed) {
    }

    public record RunResult(int trialsEnded, int invoicesIssued, int suspended) {
    }

    private final Plans plans;
    private final Subscriptions subscriptions;
    private final Invoices invoices;
    private final PlanChanges planChanges;
    private final WebhookInbox inbox;
    private final PaymentProvider payments;
    private final Directory directory;
    private final CellClient cells;
    private final Metering metering;
    private final Accounts accounts;
    private final Clock clock;
    private final TransactionTemplate tx;

    public Billing(Plans plans, Subscriptions subscriptions, Invoices invoices, PlanChanges planChanges,
                   WebhookInbox inbox, PaymentProvider payments, Directory directory, CellClient cells,
                   Metering metering, Accounts accounts, Clock clock, PlatformTransactionManager transactions) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.planChanges = planChanges;
        this.inbox = inbox;
        this.payments = payments;
        this.directory = directory;
        this.cells = cells;
        this.metering = metering;
        this.accounts = accounts;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Billing is for the people who can change the plan; members and guests get a 403. */
    public void requireBillingAdmin(TenantRoute tenant, String userId) {
        String role = cells.role(tenant.cellUrl(), tenant.id(), userId)
            .orElseThrow(() -> new ForbiddenException("not-a-member", "You are not a member of this workspace"));
        if (!BILLING_ROLES.contains(role)) {
            throw new ForbiddenException("permission-denied", "Only owners and admins can manage billing");
        }
    }

    public Overview overview(TenantRoute tenant) {
        Subscription sub = subscription(tenant.id());
        long seats = cells.seats(tenant.cellUrl(), tenant.id());
        return new Overview(sub, plan(sub.planCode()), metering.calls(tenant.id(), sub.periodStart(), sub.periodEnd()),
            seats);
    }

    public List<Invoice> invoices(UUID tenantId) {
        return invoices.forTenant(tenantId, 50);
    }

    /** Saves the card and retries whatever failed on the old one. */
    public String setPaymentMethod(UUID tenantId, String token) {
        String last4 = payments.cardFor(token)
            .orElseThrow(() -> new InvalidRequestException("invalid-payment-token", "Unknown payment token"));
        List<Invoice> retry = tx.execute(s -> {
            Subscription sub = locked(tenantId);
            subscriptions.save(with(sub, sub.planCode(), sub.status(), sub.graceUntil(), last4, sub.creditCents(),
                sub.periodStart(), sub.periodEnd()));
            return invoices.failed(tenantId);
        });
        retry.forEach(this::charge);
        return last4;
    }

    public PlanChange changePlan(TenantRoute tenant, String planCode, String key, String requestHash) {
        Plan to = plans.find(planCode)
            .orElseThrow(() -> new InvalidRequestException("unknown-plan", "No plan called " + planCode));
        long seats = cells.seats(tenant.cellUrl(), tenant.id());
        PlanChange result = tx.execute(s -> {
            // Lock first: a concurrent retry with the same key then waits here and sees the recorded change.
            Subscription sub = locked(tenant.id());
            var earlier = planChanges.find(tenant.id(), key);
            if (earlier.isPresent()) {
                if (!earlier.get().requestHash().equals(requestHash)) {
                    throw new BusinessRuleException("idempotency-key-reused",
                        "This Idempotency-Key was used for a different request");
                }
                PlanChanges.Change c = earlier.get();
                return new PlanChange(c.fromPlan(), c.toPlan(), c.proratedCents(),
                    c.invoiceId() == null ? null : invoices.find(c.invoiceId()).orElse(null), true);
            }
            if (!sub.status().allowsWrites()) {
                throw new PaymentRequiredException("tenant-suspended", "Settle the open invoices before changing plan");
            }
            if (sub.planCode().equals(to.code())) {
                throw new StateConflictException("already-on-plan", "The workspace is already on " + to.code());
            }
            if (seats > to.maxSeats()) {
                throw new StateConflictException("too-many-seats", seats + " seats are in use and " + to.code()
                    + " allows " + to.maxSeats(), Map.of("seats", seats, "maxSeats", to.maxSeats()));
            }
            Plan from = plan(sub.planCode());
            Instant now = clock.instant();
            long cents = sub.status() == SubscriptionStatus.TRIALING
                ? 0 : Pricing.proration(from, to, seats, sub.periodStart(), sub.periodEnd(), now);
            long credit = sub.creditCents();
            Invoice invoice = null;
            if (cents > 0) {
                long applied = Math.min(credit, cents);
                credit -= applied;
                invoice = new Invoice(UUID.randomUUID(), tenant.id(), Invoice.Kind.PRORATION, to.code(), now,
                    sub.periodEnd(), (int) seats, 0, cents, 0, applied, cents - applied,
                    cents == applied ? Invoice.Status.PAID : Invoice.Status.OPEN, 0, now, cents == applied ? now : null);
                invoices.insert(invoice);
            } else if (cents < 0) {
                credit += -cents;
            }
            subscriptions.save(with(sub, to.code(), sub.status(), sub.graceUntil(), sub.cardLast4(), credit,
                sub.periodStart(), sub.periodEnd()));
            planChanges.insert(tenant.id(), key, new PlanChanges.Change(requestHash, from.code(), to.code(), cents,
                invoice == null ? null : invoice.id(), now));
            return new PlanChange(from.code(), to.code(), cents, invoice, false);
        });
        if (!result.replayed()) {
            accounts.changed(tenant.id());
            if (result.invoice() != null && result.invoice().status() == Invoice.Status.OPEN) {
                charge(result.invoice());
            }
        }
        return result;
    }

    /** Ends trials, closes periods and suspends tenants past their grace. Safe to run concurrently or twice. */
    public RunResult runDue() {
        Instant now = clock.instant();
        int trials = 0;
        int issued = 0;
        for (UUID tenantId : subscriptions.due(now, 500)) {
            try {
                if (endTrial(tenantId, now)) {
                    trials++;
                }
                issued += closePeriods(tenantId, now);
            } catch (RuntimeException e) {
                // One tenant's cell being down must not stop everyone else's billing; the next run retries.
                log.warn("Billing run skipped tenant {}: {}", tenantId, e.getMessage());
            }
        }
        int suspended = 0;
        for (UUID tenantId : subscriptions.graceExpired(now, 500)) {
            if (suspend(tenantId, now)) {
                suspended++;
            }
        }
        return new RunResult(trials, issued, suspended);
    }

    public void onPaymentEvent(String eventId, String type, UUID invoiceId) {
        UUID changedTenant = tx.execute(s -> {
            Instant now = clock.instant();
            if (!inbox.firstTime(eventId, type, now)) {
                return null;
            }
            Invoice invoice = invoices.find(invoiceId).orElse(null);
            if (invoice == null || invoice.status() == Invoice.Status.PAID) {
                // Unknown invoice, or a failure for an attempt that a later one already paid.
                return null;
            }
            Subscription sub = locked(invoice.tenantId());
            switch (type) {
                case "payment.succeeded" -> {
                    invoices.markPaid(invoiceId, now);
                    boolean behind = sub.status() == SubscriptionStatus.PAST_DUE || sub.status() == SubscriptionStatus.SUSPENDED;
                    if (behind && invoices.unpaid(sub.tenantId()) == 0) {
                        subscriptions.save(with(sub, sub.planCode(), SubscriptionStatus.ACTIVE, null, sub.cardLast4(),
                            sub.creditCents(), sub.periodStart(), sub.periodEnd()));
                        return sub.tenantId();
                    }
                }
                case "payment.failed" -> {
                    invoices.markFailed(invoiceId);
                    if (sub.status() == SubscriptionStatus.ACTIVE) {
                        subscriptions.save(with(sub, sub.planCode(), SubscriptionStatus.PAST_DUE, now.plus(GRACE),
                            sub.cardLast4(), sub.creditCents(), sub.periodStart(), sub.periodEnd()));
                        return sub.tenantId();
                    }
                }
                default -> log.info("Ignoring payment event type {}", type);
            }
            return null;
        });
        if (changedTenant != null) {
            accounts.changed(changedTenant);
        }
    }

    private boolean endTrial(UUID tenantId, Instant now) {
        Boolean ended = tx.execute(s -> {
            Subscription sub = locked(tenantId);
            if (sub.status() != SubscriptionStatus.TRIALING || sub.trialEndsAt().isAfter(now)) {
                return false;
            }
            // Usage during the trial is free: the first paid period starts when the trial ends.
            subscriptions.save(with(sub, sub.planCode(), SubscriptionStatus.ACTIVE, null, sub.cardLast4(),
                sub.creditCents(), sub.trialEndsAt(), sub.trialEndsAt().plus(Accounts.PERIOD)));
            return true;
        });
        if (Boolean.TRUE.equals(ended)) {
            accounts.changed(tenantId);
        }
        return Boolean.TRUE.equals(ended);
    }

    private int closePeriods(UUID tenantId, Instant now) {
        TenantRoute tenant = directory.byId(tenantId)
            .orElseThrow(() -> new NotFoundException("tenant-not-found", "No tenant " + tenantId));
        int issued = 0;
        // Bounded so a clock jump of years cannot spin; the next run picks up where this one stopped.
        for (int i = 0; i < 12; i++) {
            Subscription before = subscription(tenantId);
            boolean billable = before.status() == SubscriptionStatus.ACTIVE || before.status() == SubscriptionStatus.PAST_DUE;
            if (!billable || before.periodEnd().isAfter(now)) {
                break;
            }
            long seats = cells.seats(tenant.cellUrl(), tenantId);
            long calls = metering.calls(tenantId, before.periodStart(), before.periodEnd());
            Invoice invoice = tx.execute(s -> {
                Subscription sub = locked(tenantId);
                if (!sub.periodStart().equals(before.periodStart())) {
                    return null;
                }
                Plan plan = plan(sub.planCode());
                long seatCents = Pricing.seats(plan, seats);
                long usageCents = Pricing.usage(plan.apiTiers(), calls);
                long applied = Math.min(sub.creditCents(), seatCents + usageCents);
                long total = seatCents + usageCents - applied;
                Instant at = clock.instant();
                Invoice issuedNow = new Invoice(UUID.randomUUID(), tenantId, Invoice.Kind.PERIOD, plan.code(),
                    sub.periodStart(), sub.periodEnd(), (int) seats, calls, seatCents, usageCents, applied, total,
                    total == 0 ? Invoice.Status.PAID : Invoice.Status.OPEN, 0, at, total == 0 ? at : null);
                boolean inserted = invoices.insert(issuedNow);
                subscriptions.save(with(sub, sub.planCode(), sub.status(), sub.graceUntil(), sub.cardLast4(),
                    sub.creditCents() - (inserted ? applied : 0), sub.periodEnd(), sub.periodEnd().plus(Accounts.PERIOD)));
                return inserted ? issuedNow : null;
            });
            if (invoice != null) {
                issued++;
                if (invoice.status() == Invoice.Status.OPEN) {
                    charge(invoice);
                }
            }
        }
        return issued;
    }

    private boolean suspend(UUID tenantId, Instant now) {
        Boolean suspended = tx.execute(s -> {
            Subscription sub = locked(tenantId);
            if (sub.status() != SubscriptionStatus.PAST_DUE || sub.graceUntil() == null || sub.graceUntil().isAfter(now)) {
                return false;
            }
            subscriptions.save(with(sub, sub.planCode(), SubscriptionStatus.SUSPENDED, sub.graceUntil(),
                sub.cardLast4(), sub.creditCents(), sub.periodStart(), sub.periodEnd()));
            return true;
        });
        if (Boolean.TRUE.equals(suspended)) {
            accounts.changed(tenantId);
        }
        return Boolean.TRUE.equals(suspended);
    }

    private void charge(Invoice invoice) {
        int attempt = invoices.nextAttempt(invoice.id());
        String card = subscription(invoice.tenantId()).cardLast4();
        payments.charge(new PaymentProvider.Charge(invoice.id(), invoice.tenantId(), invoice.totalCents(), card, attempt));
    }

    private Subscription subscription(UUID tenantId) {
        return subscriptions.find(tenantId)
            .orElseThrow(() -> new NotFoundException("subscription-not-found", "No subscription for tenant " + tenantId));
    }

    private Subscription locked(UUID tenantId) {
        return subscriptions.lock(tenantId)
            .orElseThrow(() -> new NotFoundException("subscription-not-found", "No subscription for tenant " + tenantId));
    }

    private Plan plan(String code) {
        return plans.find(code).orElseThrow(() -> new IllegalStateException("Plan " + code + " is referenced but missing"));
    }

    private static Subscription with(Subscription s, String planCode, SubscriptionStatus status, Instant graceUntil,
                                     String card, long credit, Instant periodStart, Instant periodEnd) {
        return new Subscription(s.tenantId(), planCode, status, s.trialEndsAt(), periodStart,
            periodEnd, graceUntil, card, credit, s.version());
    }
}
