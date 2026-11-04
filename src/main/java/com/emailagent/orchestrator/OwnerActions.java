package com.emailagent.orchestrator;

import com.emailagent.model.Author;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.Language;
import com.emailagent.model.OutboxKind;
import com.emailagent.model.SlotStatus;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.DraftEntity;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.FactEntity;
import com.emailagent.store.ReviewEntity;
import com.emailagent.store.SlotEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the owner does from the panel (design doc §10, §7.6), each through the orchestrator: same idempotence key,
 * lifecycle check and audit row as an agent's step. These steps are built here, never registered as beans, so no
 * tick can run them: sending a saved draft happens only when the owner clicks.
 */
@ApplicationScoped
public class OwnerActions {

    private final Orchestrator orchestrator;
    private final SendDispatcher dispatcher;

    public OwnerActions(Orchestrator orchestrator, SendDispatcher dispatcher) {
        this.orchestrator = orchestrator;
        this.dispatcher = dispatcher;
    }

    /** DRAFT_SAVED → SENT: the owner validated the reviewed draft. False if the email wasn't waiting for that. */
    public boolean sendDraft(long emailId) {
        return orchestrator.runStep(new SendSavedDraft(dispatcher.domain()), emailId);
    }

    /** DRAFT_SAVED → ESCALATED: the owner rejects the draft and will answer by hand. */
    public boolean rejectDraft(long emailId) {
        return orchestrator.runStep(new Decide("owner", EmailStatus.DRAFT_SAVED, EmailStatus.ESCALATED,
                "{\"rejected_by\":\"owner\"}"), emailId);
    }

    /** ESCALATED → CLOSED: handled outside the system (the owner answered by hand, or nothing to do). */
    public boolean closeEscalation(long emailId) {
        return orchestrator.runStep(new Decide("owner", EmailStatus.ESCALATED, EmailStatus.CLOSED,
                "{\"closed_by\":\"owner\"}"), emailId);
    }

    /**
     * DRAFT_SAVED or ESCALATED → SENT with the owner's own text (design doc §7.6 "approve, edit or reject", §6
     * "escalated → user provides input"). The text becomes the current draft, written and approved by the owner, so
     * the outbox guard sees a passed review of exactly what leaves. Throws {@link IllegalArgumentException} for an
     * empty or oversized text; false if the email wasn't waiting for that.
     */
    public boolean sendOwnerReply(long emailId, String text) {
        String body = cleanReply(text);
        EmailStatus status = QuarkusTransaction.requiringNew().call(() -> {
            EmailEntity e = EmailEntity.findById(emailId);
            return e == null ? null : e.status;
        });
        if (status != EmailStatus.DRAFT_SAVED && status != EmailStatus.ESCALATED) {
            return false;
        }
        return orchestrator.runStep(new OwnerReply(status, body, dispatcher.domain()), emailId);
    }

    /**
     * ESCALATED → READY: the writer tries again (design doc §6). Only for an email whose every slot is filled and
     * automatable: anything else would just come back, so it isn't offered. False when not eligible.
     */
    public boolean retryWriter(long emailId) {
        if (!QuarkusTransaction.requiringNew().call(() -> canRetry(emailId))) {
            return false;
        }
        return orchestrator.runStep(new Decide("owner", EmailStatus.ESCALATED, EmailStatus.READY,
                "{\"retry_by\":\"owner\"}"), emailId);
    }

    /** Call inside a transaction. The writer would refuse anything else (slots_not_filled, not_automatable). */
    public static boolean canRetry(long emailId) {
        EmailEntity e = EmailEntity.findById(emailId);
        return e != null && e.status == EmailStatus.ESCALATED && !e.slots.isEmpty()
                && e.slots.stream().allMatch(s -> s.status == SlotStatus.FILLED && s.type.automatable());
    }

    static final int MAX_REPLY = 6000;
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\n\t]]|[\\u2028\\u2029\\u202A-\\u202E\\u2066-\\u2069]");

    /** Line breaks normalized, control and bidi characters removed. The message never echoes the input. */
    static String cleanReply(String text) {
        String body = text == null ? "" : CONTROL.matcher(text.replace("\r\n", "\n").replace('\r', '\n'))
                .replaceAll("").strip();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("the reply is empty");
        }
        if (body.length() > MAX_REPLY) {
            throw new IllegalArgumentException("the reply is longer than " + MAX_REPLY + " characters");
        }
        return body;
    }

    /**
     * The owner makes the agent forget a fact (design doc §8.2, "edit, re-scope or delete any fact"): it stops being
     * used at once, and later senders get a card again. The row stays, inactive, so the audit and the correction
     * chain still know who received it. False if it was already forgotten or doesn't exist.
     */
    public boolean forgetFact(long factId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FactEntity f = FactEntity.findById(factId);
            if (f == null || !f.active) {
                return false;
            }
            f.active = false;
            DecisionLogEntry.record(null, "owner", "fact:" + factId + ":v" + f.factVersion,
                    "{\"forgot_fact\":" + factId + "}");
            return true;
        });
    }

    /**
     * "Approve all" for one card (design doc §7.6): sends its saved drafts except the outliers, which the owner opens
     * one by one. Every reply still carries its own passed review; the database checks each one again.
     */
    public int approveAll(long cardId, List<Long> outliers) {
        List<Long> ids = QuarkusTransaction.requiringNew().call(() -> SlotEntity.<SlotEntity>list(
                        "requestId = ?1 and email.status = ?2", cardId, EmailStatus.DRAFT_SAVED).stream()
                .map(s -> s.email.id).distinct().filter(id -> !outliers.contains(id)).toList());
        int sent = 0;
        for (long id : ids) {
            if (sendDraft(id)) {
                sent++;
            }
        }
        return sent;
    }

    /** The owner's send: the current draft must still be the one that passed review. */
    static final class SendSavedDraft implements AgentStep {

        private final String domain;

        SendSavedDraft(String domain) {
            this.domain = domain;
        }

        @Override
        public String name() {
            return "owner";
        }

        @Override
        public EmailStatus trigger() {
            return EmailStatus.DRAFT_SAVED;
        }

        private record Pair(Long draftId, Long reviewId) {}

        @Override
        public StepResult run(EmailSnapshot email) {
            Pair p = QuarkusTransaction.requiringNew().call(() -> {
                DraftEntity d = DraftEntity.currentFor(email.id()).orElse(null);
                ReviewEntity r = ReviewEntity.latestFor(email.id()).orElse(null);
                boolean ok = d != null && r != null && r.passed && r.draft.id.equals(d.id);
                return ok ? new Pair(d.id, r.id) : null;
            });
            if (p == null) {
                return StepResult.to(EmailStatus.ESCALATED, "{\"escalated\":\"review_mismatch\"}");
            }
            return StepResult.to(EmailStatus.SENT, "{\"sent_by\":\"owner\"}").withWrites(e ->
                    SendDispatcher.queueReply(e, p.draftId(), p.reviewId(), OutboxKind.SEND, domain));
        }
    }

    /** The owner's own reply: a new current draft by the owner, the owner's approval, then the outbox row. */
    static final class OwnerReply implements AgentStep {

        private final EmailStatus trigger;
        private final String body;
        private final String domain;

        OwnerReply(EmailStatus trigger, String body, String domain) {
            this.trigger = trigger;
            this.body = body;
            this.domain = domain;
        }

        @Override
        public String name() {
            return "owner";
        }

        @Override
        public EmailStatus trigger() {
            return trigger;
        }

        @Override
        public StepResult run(EmailSnapshot email) {
            String previous = QuarkusTransaction.requiringNew().call(() ->
                    DraftEntity.currentFor(email.id()).map(d -> d.body).orElse(null));
            // "edited" is what the metrics need: a draft sent as the agents wrote it counts as accepted, not this
            boolean edited = previous == null || !previous.strip().equals(body);
            String output = "{\"sent_by\":\"owner\",\"edited\":" + edited + ",\"from\":\""
                    + trigger.name().toLowerCase() + "\"}";
            Language lang = email.language() == null ? Language.EN : email.language();
            return StepResult.to(EmailStatus.SENT, output).withWrites(e -> {
                DraftEntity.<DraftEntity>list("email.id = ?1 and current = true", e.id).forEach(d -> d.current = false);
                DraftEntity.flush(); // one current draft per email (partial unique index)
                DraftEntity d = new DraftEntity();
                d.email = e;
                d.body = body;
                d.language = lang;
                d.factsUsed = previous == null ? "owner: written in the panel" : "owner: edited in the panel";
                d.attempts = 1;
                d.writtenBy = Author.OWNER;
                d.createdAt = Instant.now();
                d.persist();
                DraftEntity.flush();
                ReviewEntity r = new ReviewEntity();
                r.email = e;
                r.draft = d;
                r.passed = true;
                r.reviewedBy = Author.OWNER;
                r.createdAt = Instant.now();
                r.persist();
                ReviewEntity.flush();
                SendDispatcher.queueReply(e, d.id, r.id, OutboxKind.SEND, domain);
            });
        }
    }

    /** A plain decision with no writes: from one status to another, recorded under the owner's name. */
    record Decide(String name, EmailStatus trigger, EmailStatus to, String output) implements AgentStep {

        @Override
        public StepResult run(EmailSnapshot email) {
            return StepResult.to(to, output);
        }
    }
}
