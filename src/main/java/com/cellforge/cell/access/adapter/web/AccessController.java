package com.cellforge.cell.access.adapter.web;

import com.cellforge.cell.access.application.AccessService;
import com.cellforge.cell.access.application.AccessService.Accepter;
import com.cellforge.cell.access.domain.AuditEntry;
import com.cellforge.cell.access.domain.Invite;
import com.cellforge.cell.access.domain.Member;
import com.cellforge.cell.access.domain.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
class AccessController {

    record MemberView(String userId, String email, String displayName, Role role, Instant joinedAt) {
        static MemberView of(Member m) {
            return new MemberView(m.userId(), m.email(), m.displayName(), m.role(), m.joinedAt());
        }
    }

    record InviteBody(@NotBlank @Email String email, @NotNull Role role) {
    }

    /** The token appears only in the create response; delivering it is the caller's job (see README). */
    record InviteView(UUID id, String email, Role role, String invitedBy, Instant expiresAt, String token) {
        static InviteView of(Invite i, String token) {
            return new InviteView(i.id(), i.email(), i.role(), i.invitedBy(), i.expiresAt(), token);
        }
    }

    record AcceptBody(@NotBlank String token) {
    }

    record RoleBody(@NotNull Role role) {
    }

    private final AccessService access;

    AccessController(AccessService access) {
        this.access = access;
    }

    @GetMapping("/members")
    List<MemberView> members() {
        return access.members().stream().map(MemberView::of).toList();
    }

    @PatchMapping("/members/{userId}")
    MemberView changeRole(@PathVariable String userId, @Valid @RequestBody RoleBody body) {
        return MemberView.of(access.changeRole(userId, body.role()));
    }

    @DeleteMapping("/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable String userId) {
        access.remove(userId);
    }

    @PostMapping("/invites")
    @ResponseStatus(HttpStatus.CREATED)
    InviteView invite(@Valid @RequestBody InviteBody body) {
        AccessService.IssuedInvite issued = access.invite(body.email(), body.role());
        return InviteView.of(issued.invite(), issued.token());
    }

    @GetMapping("/invites")
    List<InviteView> invites() {
        return access.openInvites().stream().map(i -> InviteView.of(i, null)).toList();
    }

    @DeleteMapping("/invites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID id) {
        access.revoke(id);
    }

    @PostMapping("/invites/accept")
    MemberView accept(@Valid @RequestBody AcceptBody body, @AuthenticationPrincipal Jwt jwt) {
        String name = firstNonBlank(jwt.getClaimAsString("name"), jwt.getClaimAsString("preferred_username"), jwt.getSubject());
        return MemberView.of(access.accept(body.token(), new Accepter(jwt.getSubject(), jwt.getClaimAsString("email"), name)));
    }

    @GetMapping("/audit")
    List<AuditEntry> audit(@RequestParam(defaultValue = "50") int limit) {
        return access.audit(limit);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        throw new IllegalArgumentException("Token has no subject");
    }
}
