package com.emailagent.orchestrator;

import com.emailagent.config.AppConfig;
import com.emailagent.llm.LlmException;
import com.emailagent.model.EmailStatus;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.EmailEntity;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.OptimisticLockException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.hibernate.StaleStateException;

/**
 * Deterministic code, not an LLM (design doc §3, §6): finds emails waiting in a status, runs the one agent that
 * status triggers, checks the proposed transition and commits it. Agents never call each other or change a status.
 *
 * <p>Each step is keyed by (email id, status, version). A result is committed only if the email is still exactly
 * where the agent found it, so a restart, a second run or a concurrent worker never applies a step twice.
 */
@ApplicationScoped
public class Orchestrator {

    /** Bound on chained steps per call: an email can't loop forever between statuses. */
    static final int MAX_ROUNDS = 10;

    private final Map<EmailStatus, AgentStep> steps;
    private final int batchSize;

    public Orchestrator(Instance<AgentStep> steps, AppConfig config) {
        this.steps = routingTable(steps.stream().toList());
        this.batchSize = config.orchestratorBatchSize();
    }

    /** One step per status, never on a terminal status. A routing ambiguity stops the application at start-up. */
    static Map<EmailStatus, AgentStep> routingTable(Collection<AgentStep> steps) {
        Map<EmailStatus, AgentStep> table = new EnumMap<>(EmailStatus.class);
        for (AgentStep step : steps) {
            if (EmailLifecycle.isTerminal(step.trigger())) {
                throw new IllegalStateException("step " + step.name() + " triggers on terminal status " + step.trigger());
            }
            AgentStep previous = table.put(step.trigger(), step);
            if (previous != null) {
                throw new IllegalStateException("steps " + previous.name() + " and " + step.name()
                        + " both trigger on " + step.trigger());
            }
        }
        return table;
    }

    /** Runs every registered step until nothing moves. Returns the number of transitions made. */
    public int processPending() {
        return process(steps.values());
    }

    /** Same as {@link #processPending()} with an explicit set of steps (tests, demos). */
    public int process(Collection<AgentStep> stepsToRun) {
        Collection<AgentStep> checked = routingTable(stepsToRun).values();
        int total = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            int moved = 0;
            for (AgentStep step : checked) {
                moved += runStep(step);
            }
            total += moved;
            if (moved == 0) {
                break;
            }
        }
        return total;
    }

    /** Runs one step on the oldest emails waiting in its trigger status. */
    public int runStep(AgentStep step) {
        List<EmailSnapshot> batch = QuarkusTransaction.requiringNew().call(() ->
                EmailEntity.<EmailEntity>find("status = ?1 order by receivedAt, id", step.trigger())
                        .page(0, batchSize).list().stream().map(EmailSnapshot::of).toList());
        int moved = 0;
        for (EmailSnapshot email : batch) {
            try {
                if (runOne(step, email)) {
                    moved++;
                }
            } catch (RuntimeException e) {
                // the database itself failed; leave the email where it is, the next run retries (availability)
                Log.warnf("orchestrator could not finish %s on email %d (%s)", step.name(), email.id(),
                        e.getClass().getSimpleName());
            }
        }
        return moved;
    }

    private boolean runOne(AgentStep step, EmailSnapshot email) {
        StepResult result;
        try {
            result = step.run(email); // slow (LLM), outside any transaction
        } catch (LlmException e) {
            // the client already retried once: second failure escalates (design doc §6, retries)
            return escalate(email, step.name(), "llm_" + e.reason().name().toLowerCase());
        } catch (RuntimeException e) {
            Log.warnf("step %s failed on email %d (%s)", step.name(), email.id(), e.getClass().getSimpleName());
            return escalate(email, step.name(), "step_error");
        }
        if (result == null || !EmailLifecycle.allowed(email.status(), result.next())) {
            // an agent (or a model talking through it) asked for a jump the lifecycle forbids: refuse, hand to a human
            Log.warnf("step %s proposed an illegal transition on email %d", step.name(), email.id());
            return escalate(email, step.name(), "illegal_transition");
        }
        try {
            return commit(email, step.name(), result);
        } catch (RuntimeException e) {
            if (isConcurrentUpdate(e)) {
                return false; // someone moved the email while we committed: their result wins
            }
            Log.warnf("step %s could not save its result on email %d (%s)", step.name(), email.id(),
                    e.getClass().getSimpleName());
            return escalate(email, step.name(), "write_error");
        }
    }

    /** Writes, status change and audit row in one transaction, only if the email didn't move meanwhile. */
    private boolean commit(EmailSnapshot email, String agent, StepResult result) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EmailEntity current = EmailEntity.findById(email.id());
            if (!stillAt(current, email)) {
                Log.infof("discarding stale %s result for email %d", agent, email.id());
                return false;
            }
            result.writes().accept(current);
            current.status = result.next(); // set last: a write can't choose the status
            DecisionLogEntry.transition(email.id(), agent, email.status(), result.next(), result.output(),
                    result.confidence(), result.sources(), result.modelVersion());
            return true;
        });
    }

    /** Hands the email to a human. The reason is ours, never the model's text (it may carry the attacker's words). */
    private boolean escalate(EmailSnapshot email, String agent, String reason) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EmailEntity current = EmailEntity.findById(email.id());
            if (!stillAt(current, email) || !EmailLifecycle.allowed(current.status, EmailStatus.ESCALATED)) {
                return false;
            }
            current.status = EmailStatus.ESCALATED;
            DecisionLogEntry.transition(email.id(), agent, email.status(), EmailStatus.ESCALATED,
                    "{\"escalated\":\"" + reason + "\"}", null, null, null);
            return true;
        });
    }

    /** The idempotence key: same status and same version as when the agent read it. */
    private static boolean stillAt(EmailEntity current, EmailSnapshot seen) {
        return current != null && current.status == seen.status() && current.version == seen.version();
    }

    private static boolean isConcurrentUpdate(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof OptimisticLockException || t instanceof StaleStateException) {
                return true;
            }
        }
        return false;
    }
}
