package com.emailagent.agents;

import com.emailagent.model.EmailStatus;
import com.emailagent.model.Intent;
import com.emailagent.model.Sensitivity;
import com.emailagent.orchestrator.AgentStep;
import com.emailagent.orchestrator.EmailSnapshot;
import com.emailagent.orchestrator.StepResult;
import com.emailagent.security.InjectionDetector;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.regex.Pattern;

/**
 * TRIAGED → ESCALATED, IGNORED or RESOLVING. Plain code, no model: policy decisions are made here, from triage's
 * labels and the taxonomy's own flags, so no email text can argue its way past them (design doc §6, §16.3).
 */
@ApplicationScoped
public class TriageRouter implements AgentStep {

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
        return EmailStatus.TRIAGED;
    }

    @Override
    public StepResult run(EmailSnapshot email) {
        Route route = route(email);
        return StepResult.to(route.next(), "{\"route\":\"" + route.reason() + "\"}");
    }

    /** Order matters: anything sensitive goes to the owner, even when "no reply is needed". */
    public static Route route(EmailSnapshot e) {
        if (e.sensitivity() == null || e.replyNeeded() == null || e.intent() == null) {
            return new Route(EmailStatus.ESCALATED, "incomplete_triage"); // fail closed
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
        if (!e.replyNeeded()) {
            return new Route(EmailStatus.IGNORED, "no_reply_needed");
        }
        if (e.slots().isEmpty()) {
            // a question outside the taxonomy: a human answers it, and it's a candidate for a new slot type
            return new Route(EmailStatus.ESCALATED, "no_registered_slot");
        }
        return new Route(EmailStatus.RESOLVING, "resolve_slots");
    }
}
