package com.emailagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * In Gmail mode nothing leaves without the owner: the owner is the last check, so the panel says when the receiving
 * server didn't authenticate the sender, where the owner decides (escalations and drafts).
 */
class PanelSenderAuthTest {

    @Test
    void anUnauthenticatedSenderIsShownAsPossiblySpoofed() {
        assertThat(PanelQueries.spoofWarning(null, "hidden_html,sender_unverified")).contains("did not authenticate");
        assertThat(PanelQueries.spoofWarning("name_mixed_scripts", null)).contains("Look-alike");
        assertThat(PanelQueries.spoofWarning("override_instructions", "hidden_html")).isNull();
    }

    @Test
    void anUnauthenticatedSenderIsNamedAmongTheDraftsReasons() {
        assertThat(PanelQueries.outlierCodes("suspicious_input", "sender_unverified"))
                .containsExactly("suspicious_input", "sender_unverified");
        assertThat(PanelQueries.outlierCodes("writer_retry", "hidden_html")).containsExactly("writer_retry");
        assertThat(PanelQueries.outlierCodes(null, null)).isEmpty();
    }
}
