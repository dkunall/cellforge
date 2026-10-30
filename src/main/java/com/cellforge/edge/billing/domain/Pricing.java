package com.cellforge.edge.billing.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** All amounts are whole cents; rounding happens once, at the end of each calculation, half up. */
public final class Pricing {

    private Pricing() {
    }

    public static long seats(Plan plan, long seats) {
        return plan.seatPriceCents() * seats;
    }

    /**
     * Graduated pricing: the first band's calls at the first band's price, the next band's at its own, and so
     * on. Bands' {@code upTo} are cumulative call counts.
     */
    public static long usage(List<Plan.ApiTier> tiers, long calls) {
        long thousandthsOfCents = 0;
        long below = 0;
        for (Plan.ApiTier tier : tiers) {
            if (calls <= below) {
                break;
            }
            long top = tier.upTo() == null ? calls : Math.min(calls, tier.upTo());
            thousandthsOfCents = Math.addExact(thousandthsOfCents, Math.multiplyExact(top - below, (long) tier.centsPer1000()));
            below = top;
        }
        return divideHalfUp(BigInteger.valueOf(thousandthsOfCents), BigInteger.valueOf(1000));
    }

    /**
     * What switching from {@code from} to {@code to} at {@code at} costs for the rest of the period, per seat:
     * the unused share of the old price comes back, the same share of the new price is charged. Negative means
     * the tenant is owed money.
     */
    public static long proration(Plan from, Plan to, long seats, Instant periodStart, Instant periodEnd, Instant at) {
        long total = Duration.between(periodStart, periodEnd).toSeconds();
        long left = Math.max(0, Math.min(total, Duration.between(at, periodEnd).toSeconds()));
        if (total == 0 || left == 0) {
            return 0;
        }
        long difference = (long) (to.seatPriceCents() - from.seatPriceCents()) * seats;
        BigInteger scaled = BigInteger.valueOf(difference).multiply(BigInteger.valueOf(left));
        return divideHalfUp(scaled, BigInteger.valueOf(total));
    }

    private static long divideHalfUp(BigInteger numerator, BigInteger denominator) {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), 0, RoundingMode.HALF_UP).longValueExact();
    }
}
