package com.cellforge.edge.billing.domain;

public enum SubscriptionStatus {
    TRIALING, ACTIVE, PAST_DUE, SUSPENDED, CANCELED;

    /** Suspended and canceled workspaces stay readable so people can export, but cannot change anything. */
    public boolean allowsWrites() {
        return this != SUSPENDED && this != CANCELED;
    }
}
