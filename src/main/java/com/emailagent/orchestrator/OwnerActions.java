package com.emailagent.orchestrator;

import com.emailagent.model.EmailStatus;
import com.emailagent.model.OutboxKind;
import com.emailagent.store.DraftEntity;
import com.emailagent.store.ReviewEntity;
import com.emailagent.store.SlotEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

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

    /** A plain decision with no writes: from one status to another, recorded under the owner's name. */
    record Decide(String name, EmailStatus trigger, EmailStatus to, String output) implements AgentStep {

        @Override
        public StepResult run(EmailSnapshot email) {
            return StepResult.to(to, output);
        }
    }
}
