package com.emailagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.model.EmailStatus;
import org.junit.jupiter.api.Test;

/** An email's history reads as sentences, and who acted is always named. */
class StepTextTest {

    @Test
    void transitionsBecomeSentences() {
        assertThat(StepText.of("reviewer", EmailStatus.DRAFTED, EmailStatus.REVIEWED, "{}", null))
                .satisfies(s -> {
                    assertThat(s.actor()).isEqualTo("Reviewer");
                    assertThat(s.text()).isEqualTo("Draft passed review");
                    assertThat(s.tone()).isEqualTo("ok");
                });
        assertThat(StepText.of("owner", EmailStatus.ESCALATED, EmailStatus.SENT, "{\"sent_by\":\"owner\",\"edited\":true}",
                null).text()).isEqualTo("You sent your own version");
        assertThat(StepText.of("owner", EmailStatus.DRAFT_SAVED, EmailStatus.SENT, "{\"edited\":false}", null).text())
                .isEqualTo("You approved and sent the draft");
        assertThat(StepText.of("owner", EmailStatus.DRAFT_SAVED, EmailStatus.ESCALATED, "{\"rejected_by\":\"owner\"}",
                null).text()).isEqualTo("You rejected the draft");
        assertThat(StepText.of("triage", EmailStatus.NEW, EmailStatus.ESCALATED,
                "{\"injection\":{\"score\":1.0,\"signals\":\"hidden_html\"}}", null))
                .satisfies(s -> {
                    assertThat(s.text()).startsWith("Handed to you: possible prompt injection");
                    assertThat(s.tone()).isEqualTo("danger");
                });
    }

    @Test
    void sideStepsAreDescribedFromOurCodes() {
        assertThat(StepText.of("prefilter", null, null, "{\"filtered\":\"AUTO_REPLY\"}", null).text())
                .isEqualTo("Filtered out: auto reply");
        assertThat(StepText.of("prefilter", null, null, "{\"filtered\":null,\"flags\":\"bidi_control,invisible_unicode\"}",
                null).text()).isEqualTo("Checked before any model call: flagged bidi control, invisible unicode");
        assertThat(StepText.of("grouping", null, null, "{\"by\":\"SAME_KEY\"}", null).text())
                .isEqualTo("Joined a card waiting on the same answer");
        assertThat(StepText.of("outbox", null, null, "{\"delivered\":\"draft\"}", null).text())
                .isEqualTo("Saved in your mailbox drafts");
        assertThat(StepText.of("new_agent", null, null, "{}", null).actor()).isEqualTo("new agent");
    }
}
