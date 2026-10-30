package com.cellforge.cell.access.application;

import com.cellforge.cell.access.domain.AuditEntry;
import com.cellforge.cell.access.domain.Invite;
import com.cellforge.cell.access.domain.Member;
import com.cellforge.cell.access.domain.MembershipRules;
import com.cellforge.cell.access.domain.Permission;
import com.cellforge.cell.access.domain.Role;
import com.cellforge.cell.tenancy.application.EntitlementSource;
import com.cellforge.cell.tenancy.application.TenantContext;
import com.cellforge.cell.tenancy.domain.Entitlements;
import com.cellforge.shared.error.BusinessRuleException;
import com.cellforge.shared.error.ForbiddenException;
import com.cellforge.shared.error.NotFoundException;
import com.cellforge.shared.error.StateConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccessService {

    public record IssuedInvite(Invite invite, String token) {
    }

    /** Who is accepting, as their token says. Email may be absent; when present it must match the invite. */
    public record Accepter(String userId, String email, String displayName) {
    }

    private static final Duration INVITE_TTL = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Members members;
    private final Invites invites;
    private final AuditLog audit;
    private final EntitlementSource entitlements;
    private final Clock clock;

    public AccessService(Members members, Invites invites, AuditLog audit, EntitlementSource entitlements, Clock clock) {
        this.members = members;
        this.invites = invites;
        this.audit = audit;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Member> members() {
        TenantContext.member();
        return members.all();
    }

    @Transactional
    public IssuedInvite invite(String email, Role role) {
        Member actor = TenantContext.member();
        MembershipRules.checkInvite(actor, role);
        Entitlements plan = entitlements.forTenant(actor.tenantId());
        if (role == Role.GUEST && !plan.has("guests")) {
            throw new BusinessRuleException("feature-not-in-plan", "Guests need a plan with the guests feature");
        }
        if (role.takesSeat() && members.seats() >= plan.maxSeats()) {
            throw new BusinessRuleException("seat-limit", "All " + plan.maxSeats() + " seats on the " + plan.plan() + " plan are taken");
        }
        Instant now = clock.instant();
        String token = "inv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        Invite invite = new Invite(UUID.randomUUID(), actor.tenantId(), email.trim(), role, actor.userId(), now,
            now.plus(INVITE_TTL), null, null, null);
        try {
            invites.insert(invite, hash(token));
        } catch (DuplicateKeyException e) {
            throw new StateConflictException("invite-pending", email + " already has an open invite");
        }
        audit.record(actor.userId(), "invite.created", email, Map.of("role", role.name(), "inviteId", invite.id().toString()));
        return new IssuedInvite(invite, token);
    }

    @Transactional(readOnly = true)
    public List<Invite> openInvites() {
        TenantContext.require(Permission.MEMBER_INVITE);
        return invites.open(clock.instant());
    }

    @Transactional
    public void revoke(UUID inviteId) {
        Member actor = TenantContext.require(Permission.MEMBER_INVITE);
        if (!invites.revoke(inviteId, clock.instant())) {
            throw new NotFoundException("invite-not-found", "No open invite " + inviteId);
        }
        audit.record(actor.userId(), "invite.revoked", inviteId.toString(), Map.of());
    }

    /**
     * Seat count and acceptance happen under one per-tenant lock, so two people accepting the last seat at the
     * same moment cannot both get in.
     */
    @Transactional
    public Member accept(String token, Accepter who) {
        Invite invite = invites.findByTokenHash(hash(token))
            .orElseThrow(() -> new NotFoundException("invite-not-found", "Unknown invite"));
        if (who.userId().equals(invite.acceptedBy())) {
            return members.find(who.userId()).orElseThrow(() -> new StateConflictException("invite-closed", "Invite was used"));
        }
        Instant now = clock.instant();
        if (!invite.openAt(now)) {
            throw new StateConflictException("invite-closed", "This invite was used, revoked or has expired");
        }
        if (who.email() != null && !who.email().equalsIgnoreCase(invite.email())) {
            throw new ForbiddenException("invite-email-mismatch", "This invite was sent to another address");
        }
        members.lockSeats();
        if (members.find(who.userId()).isPresent()) {
            throw new StateConflictException("already-member", "You are already a member of this workspace");
        }
        Entitlements plan = entitlements.forTenant(invite.tenantId());
        if (invite.role().takesSeat() && members.seats() >= plan.maxSeats()) {
            throw new BusinessRuleException("seat-limit", "All " + plan.maxSeats() + " seats on the " + plan.plan() + " plan are taken");
        }
        if (!invites.markAccepted(invite.id(), who.userId(), now)) {
            throw new StateConflictException("invite-closed", "This invite was used, revoked or has expired");
        }
        Member member = new Member(invite.tenantId(), who.userId(), invite.email(), who.displayName(), invite.role(), now);
        members.insert(member);
        audit.record(who.userId(), "member.joined", who.userId(), Map.of("role", invite.role().name(),
            "invitedBy", invite.invitedBy()));
        return member;
    }

    @Transactional
    public Member changeRole(String userId, Role role) {
        Member actor = TenantContext.member();
        members.lockSeats();
        Member target = members.find(userId).orElseThrow(() -> new NotFoundException("member-not-found", "No member " + userId));
        MembershipRules.checkRoleChange(actor, target, role, members.countByRole(Role.OWNER));
        if (target.role() == role) {
            return target;
        }
        Entitlements plan = entitlements.forTenant(actor.tenantId());
        if (role.takesSeat() && !target.role().takesSeat() && members.seats() >= plan.maxSeats()) {
            throw new BusinessRuleException("seat-limit", "No free seat to promote a guest");
        }
        if (role == Role.GUEST && !plan.has("guests")) {
            throw new BusinessRuleException("feature-not-in-plan", "Guests need a plan with the guests feature");
        }
        members.updateRole(userId, role);
        audit.record(actor.userId(), "member.role_changed", userId, Map.of("from", target.role().name(), "to", role.name()));
        return new Member(target.tenantId(), target.userId(), target.email(), target.displayName(), role, target.joinedAt());
    }

    @Transactional
    public void remove(String userId) {
        Member actor = TenantContext.member();
        members.lockSeats();
        Member target = members.find(userId).orElseThrow(() -> new NotFoundException("member-not-found", "No member " + userId));
        MembershipRules.checkRemoval(actor, target, members.countByRole(Role.OWNER));
        members.delete(userId);
        audit.record(actor.userId(), "member.removed", userId, Map.of("role", target.role().name()));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> audit(int limit) {
        Member actor = TenantContext.require(Permission.AUDIT_READ);
        if (!entitlements.forTenant(actor.tenantId()).has("audit_log")) {
            throw new BusinessRuleException("feature-not-in-plan", "The audit log needs a plan with the audit_log feature");
        }
        return audit.latest(Math.min(Math.max(limit, 1), 200));
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
