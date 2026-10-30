package com.cellforge.edge.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PricingTest {

    static final List<Plan.ApiTier> PRO_TIERS = List.of(new Plan.ApiTier(100_000L, 0), new Plan.ApiTier(500_000L, 40),
        new Plan.ApiTier(null, 25));
    static final Plan PRO = plan("PRO", 800, PRO_TIERS);
    static final Plan ENTERPRISE = plan("ENTERPRISE", 1500, List.of(new Plan.ApiTier(1_000_000L, 0), new Plan.ApiTier(null, 20)));

    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant END = START.plus(Duration.ofDays(30));

    @Test
    void seatsAreFlatPerSeat() {
        assertThat(Pricing.seats(PRO, 3)).isEqualTo(2400);
        assertThat(Pricing.seats(PRO, 0)).isZero();
    }

    @Test
    void includedCallsAreFree() {
        assertThat(Pricing.usage(PRO_TIERS, 0)).isZero();
        assertThat(Pricing.usage(PRO_TIERS, 100_000)).isZero();
    }

    @Test
    void eachBandIsPricedAtItsOwnRate() {
        assertThat(Pricing.usage(PRO_TIERS, 101_000)).isEqualTo(40);
        // 400k calls at 40c per 1000, then 100k at 25c.
        assertThat(Pricing.usage(PRO_TIERS, 600_000)).isEqualTo(16_000 + 2_500);
    }

    @Test
    void usageRoundsOnceAtTheEnd() {
        assertThat(Pricing.usage(PRO_TIERS, 100_012)).isZero();
        assertThat(Pricing.usage(PRO_TIERS, 100_013)).isEqualTo(1);
    }

    @Test
    void upgradingHalfwayChargesHalfTheDifference() {
        assertThat(Pricing.proration(PRO, ENTERPRISE, 2, START, END, START.plus(Duration.ofDays(15)))).isEqualTo(700);
        assertThat(Pricing.proration(PRO, ENTERPRISE, 2, START, END, START)).isEqualTo(1400);
    }

    @Test
    void downgradingIsACredit() {
        assertThat(Pricing.proration(ENTERPRISE, PRO, 2, START, END, START.plus(Duration.ofDays(15)))).isEqualTo(-700);
    }

    @Test
    void prorationRoundsHalfUp() {
        // 700 for a third of the period is 233.33; for two thirds, 466.67.
        assertThat(Pricing.proration(PRO, ENTERPRISE, 1, START, END, START.plus(Duration.ofDays(20)))).isEqualTo(233);
        assertThat(Pricing.proration(PRO, ENTERPRISE, 1, START, END, START.plus(Duration.ofDays(10)))).isEqualTo(467);
    }

    @Test
    void nothingIsOwedOnceThePeriodIsOver() {
        assertThat(Pricing.proration(PRO, ENTERPRISE, 2, START, END, END)).isZero();
        assertThat(Pricing.proration(PRO, ENTERPRISE, 2, START, END, END.plusSeconds(60))).isZero();
    }

    private static Plan plan(String code, int seatCents, List<Plan.ApiTier> tiers) {
        return new Plan(code, code, seatCents, 0, 50, 50, 600, 100, 1_000_000, Duration.ofSeconds(5), 16, Set.of(), tiers);
    }
}
