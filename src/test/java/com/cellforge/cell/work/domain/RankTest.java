package com.cellforge.cell.work.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class RankTest {

    @Test
    void firstKeyIsMidRange() {
        assertThat(Rank.between(null, null)).isEqualTo("V");
    }

    @Test
    void rejectsNeighboursOutOfOrder() {
        assertThatThrownBy(() -> Rank.between("b", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Rank.between("a", "a")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void squeezesBetweenAdjacentDigits() {
        String key = Rank.between("a", "b");
        assertThat(key).isGreaterThan("a").isLessThan("b").startsWith("a");
    }

    @Test
    void handlesTheBottomEdge() {
        String key = Rank.between(null, "1");
        assertThat(key).isLessThan("1").isNotEmpty().doesNotEndWith("0");
    }

    /** Builds a column by random inserts and checks it stays strictly ordered with no dead ends. */
    @Property(tries = 200)
    void randomInsertsStayOrdered(@ForAll long seed, @ForAll @IntRange(min = 1, max = 300) int inserts) {
        Random random = new Random(seed);
        List<String> column = new ArrayList<>();
        for (int i = 0; i < inserts; i++) {
            int at = random.nextInt(column.size() + 1);
            String before = at == 0 ? null : column.get(at - 1);
            String after = at == column.size() ? null : column.get(at);
            String key = Rank.between(before, after);
            if (before != null) {
                assertThat(key).isGreaterThan(before);
            }
            if (after != null) {
                assertThat(key).isLessThan(after);
            }
            assertThat(key).doesNotEndWith("0");
            column.add(at, key);
        }
    }

    @Test
    void insertingAtTheSameSpotGrowsSlowly() {
        String low = "V";
        String high = "W";
        int moves = 0;
        String key = "";
        while (key.length() < Rank.REBALANCE_LENGTH) {
            key = Rank.between(low, high);
            high = key;
            moves++;
        }
        // The worst case for this scheme: everyone keeps dropping cards right after the same one.
        assertThat(moves).isGreaterThan(100);
    }

    @Property(tries = 100)
    void spreadIsOrderedAndShort(@ForAll @IntRange(min = 1, max = 5000) int count) {
        List<String> keys = Rank.spread(count);
        assertThat(keys).hasSize(count).isSorted().doesNotHaveDuplicates();
        assertThat(keys).allSatisfy(k -> assertThat(k).doesNotEndWith("0").hasSizeLessThanOrEqualTo(4));
        assertThat(Rank.between(null, keys.getFirst())).isLessThan(keys.getFirst());
        assertThat(Rank.between(keys.getLast(), null)).isGreaterThan(keys.getLast());
    }
}
