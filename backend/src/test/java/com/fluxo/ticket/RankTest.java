package com.fluxo.ticket;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RankTest {

    @Test
    void generatesStrictlyBetween() {
        assertThat(Rank.between(null, null)).isEqualTo("i");
        assertThat(Rank.between("a", "b")).isBetween("a", "b").isNotIn("a", "b");
        assertThat(Rank.between("a", "a1")).isGreaterThan("a").isLessThan("a1");
        assertThat(Rank.between(null, "1")).isLessThan("1");
        assertThat(Rank.between("zz", null)).isGreaterThan("zz");
    }

    @Test
    void randomInsertionsKeepOrderAndStayShort() {
        Random random = new Random(42);
        List<String> ranks = new ArrayList<>();
        ranks.add(Rank.between(null, null));
        for (int i = 0; i < 5_000; i++) {
            int pos = random.nextInt(ranks.size() + 1);
            String before = pos == 0 ? null : ranks.get(pos - 1);
            String after = pos == ranks.size() ? null : ranks.get(pos);
            String r = Rank.between(before, after);
            if (before != null) assertThat(r).isGreaterThan(before);
            if (after != null) assertThat(r).isLessThan(after);
            assertThat(r).doesNotEndWith("0");
            ranks.add(pos, r);
        }
        assertThat(ranks.stream().mapToInt(String::length).max().getAsInt()).isLessThan(20);
    }

    @Test
    void appendingAtTheEndGrowsSlowly() {
        String r = Rank.between(null, null);
        for (int i = 0; i < 10_000; i++) {
            String next = Rank.between(r, null);
            assertThat(next).isGreaterThan(r);
            r = next;
        }
        assertThat(r).hasSize(6);
        String first = Rank.between(null, null);
        for (int i = 0; i < 10_000; i++) {
            String prev = Rank.between(null, first);
            assertThat(prev).isLessThan(first).doesNotEndWith("0");
            first = prev;
        }
        assertThat(first).hasSize(6);
    }
}
