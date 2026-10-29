package com.emailagent.orchestrator;

import static com.emailagent.model.EmailStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.emailagent.model.EmailStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The state machine itself, and the start-up checks on how agents are wired to it. */
class EmailLifecycleTest {

    @Test
    void nothingReachesSentWithoutTheReviewer() {
        for (EmailStatus from : EmailStatus.values()) {
            if (EmailLifecycle.allowed(from, SENT)) {
                assertThat(from).isIn(REVIEWED, DRAFT_SAVED);
            }
        }
        assertThat(EmailLifecycle.allowed(NEW, SENT)).isFalse();
        assertThat(EmailLifecycle.allowed(DRAFTED, SENT)).isFalse();
    }

    @Test
    void everyActiveStatusCanEscalateAndTerminalOnesGoNowhere() {
        for (EmailStatus s : EmailStatus.values()) {
            if (EmailLifecycle.isTerminal(s)) {
                assertThat(EmailLifecycle.next(s)).isEmpty();
            } else if (s != ESCALATED) {
                assertThat(EmailLifecycle.allowed(s, ESCALATED)).as(s + " -> ESCALATED").isTrue();
            }
        }
        assertThat(List.of(values())).filteredOn(EmailLifecycle::isTerminal)
                .containsExactlyInAnyOrder(FILTERED_OUT, IGNORED, SENT, CLOSED);
    }

    @Test
    void noStatusGoesBackToNew() {
        for (EmailStatus from : EmailStatus.values()) {
            assertThat(EmailLifecycle.allowed(from, NEW)).isFalse();
        }
    }

    @Test
    void twoAgentsOnOneStatusStopTheApplication() {
        AgentStep a = OrchestratorTest.step("triage", NEW, e -> StepResult.to(TRIAGED, "{}"));
        AgentStep b = OrchestratorTest.step("other", NEW, e -> StepResult.to(ESCALATED, "{}"));
        assertThatThrownBy(() -> Orchestrator.routingTable(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("both trigger on NEW");
    }

    @Test
    void anAgentOnATerminalStatusStopsTheApplication() {
        AgentStep resend = OrchestratorTest.step("resend", SENT, e -> StepResult.to(SENT, "{}"));
        assertThatThrownBy(() -> Orchestrator.routingTable(List.of(resend)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("terminal");
    }
}
