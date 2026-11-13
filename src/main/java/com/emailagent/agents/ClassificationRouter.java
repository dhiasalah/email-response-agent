package com.emailagent.agents;

import com.emailagent.mailbox.SenderDirectory;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.Intent;
import com.emailagent.model.Sensitivity;
import com.emailagent.security.SenderAuthentication;
import com.emailagent.step.AgentStep;
import com.emailagent.step.EmailSnapshot;
import com.emailagent.step.StepResult;
import com.emailagent.security.InjectionDetector;
import com.emailagent.store.EmailEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.regex.Pattern;

/**
 * CLASSIFIED → ESCALATED, IGNORED or RESOLVING: the routing agent (brief §4.1). Plain code, no model. It makes two
 * decisions: which specialized agent the email belongs to ({@link AgentRouter}: whose prompt and whose context will
 * be used), and the policy route, from the classifier's labels and the taxonomy's own flags, so no email text can
 * argue its way past them (design doc §6, §16.3). Every email that reaches this step gets an agent, escalated ones
 * included: the owner sees who would have handled it, and a retry after escalation goes to that agent.
 */
@ApplicationScoped
public class ClassificationRouter implements AgentStep {

    public record Route(EmailStatus next, String reason) {}

    /** Blocked topics (design doc §9), matched without accents: a backstop that doesn't trust the model's label. */
    static final Pattern SENSITIVE_TOPICS = Pattern.compile(
            "\\b(avocats?|lawyers?|attorneys?|mediateur|mediator|tribunal|lawsuit|plainte|"
                    + "malade|maladie|sick|illness|hopital|hospital|medecin|doctor|certificat medical|"
                    + "medical certificate|handicap|disability|deces|funeral|contest\\w*|injuste|unfair)\\b");

    @Override
    public String name() {
        return "router";
    }

    @Override
    public EmailStatus trigger() {
        return EmailStatus.CLASSIFIED;
    }

    private final AgentRouter agents;
    private final SenderDirectory directory;

    public ClassificationRouter(AgentRouter agents, SenderDirectory directory) {
        this.agents = agents;
        this.directory = directory;
    }

    @Override
    public StepResult run(EmailSnapshot email) {
        AgentRouter.Decision agent = agents.route(input(email));
        Route route = route(email);
        return new StepResult(route.next(), "{\"route\":\"" + route.reason() + "\"," + agent.json() + "}",
                agent.confidence(), null, null, e -> e.agentId = agent.agentId());
    }

    /** What the agent router may use: the roster's role, authentication, the text, and the thread's earlier agent. */
    RoutingStrategy.Input input(EmailSnapshot email) {
        boolean authenticated = !SenderAuthentication.flagged(email.sanitizerFlags());
        return new RoutingStrategy.Input(email.sender(), authenticated,
                directory.roleOf(email.sender(), email.sanitizerFlags()), email.subject(), email.body(),
                threadAgent(email));
    }

    /** The agent of the latest earlier email in the same thread, if any (a short read-only lookup). */
    static String threadAgent(EmailSnapshot email) {
        if (email.threadId() == null || email.threadId().isBlank()) {
            return null;
        }
        return QuarkusTransaction.requiringNew().call(() -> EmailEntity.<EmailEntity>find(
                        "threadId = ?1 and id <> ?2 and agentId is not null order by receivedAt desc, id desc",
                        email.threadId(), email.id())
                .firstResultOptional().map(e -> e.agentId).orElse(null));
    }

    /** Order matters: anything sensitive goes to the owner, even when "no reply is needed". */
    public static Route route(EmailSnapshot e) {
        if (e.sensitivity() == null || e.replyNeeded() == null || e.intent() == null) {
            return new Route(EmailStatus.ESCALATED, "incomplete_classification"); // fail closed
        }
        String text = InjectionDetector.fold((e.subject() == null ? "" : e.subject()) + " "
                + (e.body() == null ? "" : e.body()));
        if (SENSITIVE_TOPICS.matcher(text).find()) {
            return new Route(EmailStatus.ESCALATED, "sensitive_keyword");
        }
        if (e.sensitivity() != Sensitivity.LOW) {
            return new Route(EmailStatus.ESCALATED, "sensitive_" + e.sensitivity().name().toLowerCase());
        }
        if (e.intent() == Intent.COMPLAINT) {
            return new Route(EmailStatus.ESCALATED, "complaint");
        }
        if (e.slots().stream().anyMatch(s -> !s.type().automatable())) {
            return new Route(EmailStatus.ESCALATED, "not_automatable");
        }
        // a question with a registered slot needs an answer, whatever the model's flag says. Measured on qwen2.5:
        // "When will the S2 grades be published?" came back INFORMATION, replyNeeded=false, with its slot
        if (!e.replyNeeded() && e.slots().isEmpty()) {
            return new Route(EmailStatus.IGNORED, "no_reply_needed");
        }
        if (e.slots().isEmpty()) {
            // a question outside the taxonomy: a human answers it, and it's a candidate for a new slot type
            return new Route(EmailStatus.ESCALATED, "no_registered_slot");
        }
        return new Route(EmailStatus.RESOLVING, "resolve_slots");
    }
}
