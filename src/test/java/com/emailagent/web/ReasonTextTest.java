package com.emailagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Every escalation the panel shows reads as a sentence, and a code we don't know yet still shows as words. */
class ReasonTextTest {

    @Test
    void injectionSignalsBecomeAListOfWhatWasFound() {
        ReasonText.Reason r = ReasonText.of("classifier",
                "{\"model_called\":false,\"injection\":{\"score\":2.8,\"signals\":\"override_instructions,name_mixed_scripts,new_trick\"}}");

        assertThat(r.kind()).isEqualTo("security");
        assertThat(r.title()).startsWith("Possible prompt injection");
        assertThat(r.details()).containsExactly("tries to override the agent's instructions",
                "sender name uses look-alike letters (possible impersonation)", "new trick");
    }

    @Test
    void reviewerFailuresNameEachProblem() {
        ReasonText.Reason r = ReasonText.of("reviewer",
                "{\"passed\":false,\"rules\":[\"unsupported_link\"],\"issues\":[\"NEW_COMMITMENT\",\"IMPOLITE\"],\"outliers\":[]}");

        assertThat(r.kind()).isEqualTo("review");
        assertThat(r.details()).containsExactly("The reply contains a link no source supports",
                "The reply promises something you didn't decide", "The reply has a tone judged impolite");
    }

    @Test
    void routesOwnerActionsAndFailuresHaveTitles() {
        assertThat(ReasonText.of("router", "{\"route\":\"sensitive_health\"}").title()).contains("health");
        assertThat(ReasonText.of("owner", "{\"rejected_by\":\"owner\"}").title()).isEqualTo("You rejected the draft");
        assertThat(ReasonText.of("classifier", "{\"escalated\":\"llm_timeout\"}").title())
                .isEqualTo("The local model failed (timeout)");
        assertThat(ReasonText.of("x", "not json").title()).isEqualTo("Escalated by x");
        assertThat(ReasonText.of(null, null).title()).isEqualTo("Escalated");
    }
}
