package com.cellforge.cell.work.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MentionsTest {

    @Test
    void findsHandlesOnce() {
        assertThat(Mentions.in("@Ada can you look? cc @bob.k and @ada again.")).containsExactly("ada", "bob.k");
    }

    @Test
    void ignoresEmailAddressesAndTrailingPunctuation() {
        assertThat(Mentions.in("mail kunal@example.com or ping @kunal.")).containsExactly("kunal");
    }

    @Test
    void emptyWhenNoMentions() {
        assertThat(Mentions.in("no one here @ all")).isEmpty();
    }
}
