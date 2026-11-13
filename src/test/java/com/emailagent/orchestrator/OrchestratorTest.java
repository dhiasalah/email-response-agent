package com.emailagent.orchestrator;

import static com.emailagent.model.EmailStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.llm.LlmException;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.Intent;
import com.emailagent.step.AgentStep;
import com.emailagent.step.EmailSnapshot;
import com.emailagent.step.StepResult;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.EmailEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The orchestrator with scripted agents. Emails start at READY so the demo inbox (other tests, status NEW) is
 * never touched; assertions only look at the email each test created.
 */
@QuarkusTest
class OrchestratorTest {

    @Inject Orchestrator orchestrator;

    @Test
    void eachStepRunsOnceAndEveryTransitionIsLogged() {
        long id = newEmail(READY);
        AtomicInteger writerCalls = new AtomicInteger();
        AtomicInteger reviewerCalls = new AtomicInteger();
        List<AgentStep> steps = List.of(
                step("writer", READY, e -> {
                    if (e.id() == id) writerCalls.incrementAndGet();
                    return StepResult.to(DRAFTED, "{\"draft\":\"ok\"}");
                }),
                step("reviewer", DRAFTED, e -> {
                    if (e.id() == id) reviewerCalls.incrementAndGet();
                    return StepResult.to(REVIEWED, "{\"pass\":true}");
                }));

        orchestrator.process(steps);
        orchestrator.process(steps); // a second run (restart, next tick) changes nothing

        assertThat(writerCalls).hasValue(1);
        assertThat(reviewerCalls).hasValue(1);
        assertThat(load(id).status).isEqualTo(REVIEWED);
        assertThat(log(id)).extracting(l -> l.agent + ":" + l.fromStatus + "->" + l.toStatus)
                .containsExactly("writer:READY->DRAFTED", "reviewer:DRAFTED->REVIEWED");
    }

    @Test
    void aResultComputedOnOldDataIsDiscardedAndRecomputed() {
        long id = newEmail(READY);
        AtomicInteger calls = new AtomicInteger();
        AgentStep writer = step("writer", READY, e -> {
            if (e.id() == id && calls.incrementAndGet() == 1) {
                // while the model "thinks", someone else changes the email (status unchanged, version bumped)
                QuarkusTransaction.requiringNew().run(() -> EmailEntity.<EmailEntity>findById(id).subject = "edited");
            }
            return StepResult.to(DRAFTED, "{}");
        });

        orchestrator.runStep(writer);
        assertThat(load(id).status).isEqualTo(READY); // stale draft thrown away
        assertThat(log(id)).isEmpty();

        orchestrator.runStep(writer); // next run works on the fresh version
        assertThat(calls).hasValue(2);
        assertThat(load(id).status).isEqualTo(DRAFTED);
        assertThat(log(id)).hasSize(1);
    }

    @Test
    void anObedientAgentCannotSkipTheReviewer() {
        long id = newEmail(READY);
        // the model obeyed "send this right away": the agent proposes READY -> SENT
        AgentStep writer = step("writer", READY, e -> StepResult.to(SENT, "{}"));

        orchestrator.runStep(writer);

        assertThat(load(id).status).isEqualTo(ESCALATED);
        assertThat(log(id)).singleElement().satisfies(l -> {
            assertThat(l.toStatus).isEqualTo(ESCALATED);
            assertThat(l.output).isEqualTo("{\"escalated\":\"illegal_transition\"}");
        });
    }

    @Test
    void aModelFailureEscalates() {
        long id = newEmail(READY);
        AgentStep writer = step("writer", READY, e -> {
            throw new LlmException(LlmException.Reason.INVALID_OUTPUT, "second invalid answer");
        });

        orchestrator.runStep(writer);

        assertThat(load(id).status).isEqualTo(ESCALATED);
        assertThat(log(id)).singleElement()
                .satisfies(l -> assertThat(l.output).isEqualTo("{\"escalated\":\"llm_invalid_output\"}"));
    }

    @Test
    void writesAndStatusChangeCommitTogetherOrNotAtAll() {
        long id = newEmail(READY);
        AgentStep writer = step("writer", READY, e -> StepResult.to(DRAFTED, "{}").withWrites(entity -> {
            entity.intent = Intent.QUESTION;
            throw new IllegalStateException("disk full halfway through the writes");
        }));

        orchestrator.runStep(writer);

        EmailEntity email = load(id);
        assertThat(email.intent).isNull(); // the half-done write was rolled back with the transition
        assertThat(email.status).isEqualTo(ESCALATED);
        assertThat(log(id)).extracting(l -> l.output).containsExactly("{\"escalated\":\"write_error\"}");
    }

    @Test
    void aWriteCannotChooseTheStatus() {
        long id = newEmail(READY);
        AgentStep writer = step("writer", READY, e -> StepResult.to(DRAFTED, "{}")
                .withWrites(entity -> entity.status = SENT));

        orchestrator.runStep(writer);

        assertThat(load(id).status).isEqualTo(DRAFTED);
    }

    // --- helpers ---

    interface Behaviour {
        StepResult run(EmailSnapshot email) throws LlmException;
    }

    static AgentStep step(String name, EmailStatus trigger, Behaviour behaviour) {
        return new AgentStep() {
            @Override public String name() { return name; }
            @Override public EmailStatus trigger() { return trigger; }
            @Override public StepResult run(EmailSnapshot email) throws LlmException { return behaviour.run(email); }
        };
    }

    private static long newEmail(EmailStatus status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EmailEntity email = new EmailEntity();
            email.agentId = "student";
            email.messageId = "<orch-" + UUID.randomUUID() + "@test>";
            email.sender = "lina@univ.example";
            email.subject = "Notes S2";
            email.body = "Quand les notes seront-elles publiées ?";
            email.receivedAt = Instant.now();
            email.status = status;
            email.persist();
            return email.id;
        });
    }

    private static EmailEntity load(long id) {
        return QuarkusTransaction.requiringNew().call(() -> EmailEntity.<EmailEntity>findById(id));
    }

    private static List<DecisionLogEntry> log(long id) {
        return QuarkusTransaction.requiringNew().call(() -> DecisionLogEntry.forEmail(id));
    }
}
