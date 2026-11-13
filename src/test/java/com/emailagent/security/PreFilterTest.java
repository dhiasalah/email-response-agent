package com.emailagent.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.model.IncomingEmail;
import com.emailagent.model.FilterReason;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Emails that must never get an automatic reply are dropped before any model sees them. */
class PreFilterTest {

    private final PreFilter filter = new PreFilter("prof.martin@univ.example", 10);

    private static IncomingEmail email(String from, String subject, Map<String, String> headers) {
        return new IncomingEmail("<id>", "t", from, "Name", subject, "body", null, Instant.now(), headers);
    }

    @Test
    void ordinaryStudentEmailPasses() {
        assertThat(filter.check(email("lina@student.univ.example", "Notes du S2", Map.of()), 0)).isEmpty();
    }

    @Test
    void autoRepliesAreDroppedToBreakLoops() {
        assertThat(filter.check(email("a@x.example", "Hello", Map.of("auto-submitted", "auto-replied")), 0))
                .contains(FilterReason.AUTO_REPLY);
        assertThat(filter.check(email("a@x.example", "Hello", Map.of("Precedence", "bulk")), 0))
                .contains(FilterReason.AUTO_REPLY);
        assertThat(filter.check(email("a@x.example", "Réponse automatique : absent", Map.of()), 0))
                .contains(FilterReason.AUTO_REPLY);
        assertThat(filter.check(email("a@x.example", "Hello", Map.of("Auto-Submitted", "no")), 0)).isEmpty();
    }

    @Test
    void listsNoReplyAndOwnAddressAreDropped() {
        assertThat(filter.check(email("news@lists.example", "News", Map.of("List-Id", "<n>")), 0))
                .contains(FilterReason.MAILING_LIST);
        assertThat(filter.check(email("no-reply@platform.example", "Devoir", Map.of()), 0))
                .contains(FilterReason.NO_REPLY_SENDER);
        assertThat(filter.check(email("Prof.Martin@UNIV.example", "Re: notes", Map.of()), 0))
                .contains(FilterReason.OWN_ADDRESS);
    }

    @Test
    void floodsAndHugeEmailsAreDropped() {
        assertThat(filter.check(email("spam@x.example", "Hi", Map.of()), 10)).contains(FilterReason.RATE_LIMITED);
        IncomingEmail huge = new IncomingEmail("<id>", "t", "a@x.example", "A", "Hi",
                "x".repeat(PreFilter.MAX_RAW_CHARS + 1), null, Instant.now(), Map.of());
        assertThat(filter.check(huge, 0)).contains(FilterReason.TOO_LARGE);
    }
}
