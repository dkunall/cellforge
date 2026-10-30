package com.cellforge.cell.tenancy.application;

import com.cellforge.cell.access.domain.Member;
import com.cellforge.cell.access.domain.Permission;
import com.cellforge.shared.error.ForbiddenException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The tenant a piece of work runs for, carried on the thread for the length of a request. Every transaction
 * reads it to scope its SQL (see {@code RlsTransactionManager}); without one, row-level security hides every row.
 * A ThreadLocal rather than ScopedValue because ScopedValue is still a preview API in Java 21.
 */
public final class TenantContext {

    public enum Mode {
        /** A member's request through the edge. Writes are refused while the tenant is frozen. */
        MEMBER,
        /** The control plane provisioning, moving or inspecting a tenant. Not subject to the write freeze. */
        INTERNAL
    }

    public record Scope(UUID tenantId, Mode mode, Member member, Duration statementTimeout) {

        public static Scope internal(UUID tenantId) {
            return new Scope(tenantId, Mode.INTERNAL, null, Duration.ofSeconds(30));
        }

        public Scope withMember(Member resolved, Duration timeout) {
            return new Scope(tenantId, mode, resolved, timeout);
        }
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<Scope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID tenantId() {
        return scope().tenantId();
    }

    public static Member member() {
        Member member = scope().member();
        if (member == null) {
            throw new IllegalStateException("No member in an internal scope");
        }
        return member;
    }

    public static Member require(Permission permission) {
        Member member = member();
        if (!member.can(permission)) {
            throw new ForbiddenException("permission-denied", "Your role (" + member.role() + ") cannot " + permission);
        }
        return member;
    }

    public static <T> T call(Scope scope, Supplier<T> work) {
        Scope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void run(Scope scope, Runnable work) {
        call(scope, () -> {
            work.run();
            return null;
        });
    }

    private static Scope scope() {
        Scope scope = CURRENT.get();
        if (scope == null) {
            throw new IllegalStateException("No tenant in scope");
        }
        return scope;
    }
}
