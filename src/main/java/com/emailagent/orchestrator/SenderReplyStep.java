package com.emailagent.orchestrator;

import com.emailagent.agents.TriageAgent;
import com.emailagent.llm.LlmException;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.Language;
import com.emailagent.model.NoticeKind;
import com.emailagent.model.OutboxKind;
import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotStatus;
import com.emailagent.model.SlotType;
import com.emailagent.step.AgentStep;
import com.emailagent.step.EmailSnapshot;
import com.emailagent.step.StepResult;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.NoticeEntity;
import com.emailagent.store.OutboxEntity;
import com.emailagent.store.SlotEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Everything that happens to an email waiting on its sender (design doc §7.5, §6 timeouts). Plain code; the only
 * model call is triage itself, run again on the merged email. Run by {@link FollowUpRun} through the orchestrator,
 * on emails that have something to do, so it is not one of the steps every tick polls.
 *
 * <ul>
 *   <li>the sender answered → triage again on the merged text (its slots are read again) → TRIAGED;</li>
 *   <li>no question asked yet, or the last answer still left gaps → ask the next round, stay AWAITING_SENDER;</li>
 *   <li>two rounds answered and still gaps → ESCALATED: the owner takes over;</li>
 *   <li>the last question unanswered past the timeout → CLOSED.</li>
 * </ul>
 */
public class SenderReplyStep implements AgentStep {

    /** Design doc §7.5: at most two clarification rounds per thread (the database holds the same limit). */
    public static final int MAX_ROUNDS = 2;

    private final TriageAgent triage;
    private final SendDispatcher dispatcher;
    private final boolean noticeAutoSend;
    private final Duration timeout;
    private final String ownerName;
    private final boolean disclosure;

    public SenderReplyStep(TriageAgent triage, SendDispatcher dispatcher, boolean noticeAutoSend, Duration timeout,
            String ownerName, boolean disclosure) {
        this.triage = triage;
        this.dispatcher = dispatcher;
        this.noticeAutoSend = noticeAutoSend;
        this.timeout = timeout;
        this.ownerName = ownerName;
        this.disclosure = disclosure;
    }

    @Override
    public String name() {
        return "clarification";
    }

    @Override
    public EmailStatus trigger() {
        return EmailStatus.AWAITING_SENDER;
    }

    private record Latest(int round, Instant createdAt, boolean answered) {}

    private record Loaded(boolean replied, Latest latest, long sentToSender, long sentTotal) {}

    @Override
    public StepResult run(EmailSnapshot email) throws LlmException {
        Loaded loaded = QuarkusTransaction.requiringNew().call(() -> {
            EmailEntity e = EmailEntity.findById(email.id());
            Latest latest = NoticeEntity.latestClarification(email.id())
                    .map(n -> new Latest(n.round, n.createdAt, n.answeredAt != null)).orElse(null);
            long[] sent = SendDispatcher.sentInLastDay(email.sender());
            return new Loaded(e != null && e.senderReplied, latest, sent[0], sent[1]);
        });

        if (loaded.replied()) {
            // the answer is in the email's text now: triage reads the whole thing again, detector first
            StepResult triaged = triage.run(email);
            return new StepResult(triaged.next(), triaged.output(), triaged.confidence(), triaged.sources(),
                    triaged.modelVersion(), e -> {
                        SlotEntity.delete("email.id", e.id);
                        SlotEntity.flush();
                        e.senderReplied = false;
                        triaged.writes().accept(e);
                    });
        }
        Latest latest = loaded.latest();
        if (latest != null && !latest.answered()) {
            if (latest.createdAt().isBefore(Instant.now().minus(timeout))) {
                return StepResult.to(EmailStatus.CLOSED, "{\"closed\":\"clarification_timeout\"}");
            }
            return StepResult.waiting(EmailStatus.AWAITING_SENDER);
        }
        int round = latest == null ? 1 : latest.round() + 1;
        if (round > MAX_ROUNDS) {
            return StepResult.to(EmailStatus.ESCALATED, "{\"escalated\":\"clarification_rounds_exhausted\"}");
        }
        List<SlotType> missing = email.slots().stream()
                .filter(s -> s.owner() == SlotOwner.SENDER && s.status() == SlotStatus.UNRESOLVED)
                .map(EmailSnapshot.Slot::type).distinct().toList();
        Language lang = email.language() == null ? Language.EN : email.language();
        Optional<String> body = NoticeTemplates.clarification(missing, lang, ownerName, disclosure);
        if (body.isEmpty()) {
            return StepResult.to(EmailStatus.ESCALATED, "{\"escalated\":\"nothing_to_ask\"}");
        }
        OutboxKind kind = SendDispatcher.noticeKind(dispatcher.policy(), noticeAutoSend, loaded.sentToSender(),
                loaded.sentTotal());
        String output = "{\"clarification\":" + round + ",\"mode\":\"" + kind.name().toLowerCase() + "\"}";
        // stays AWAITING_SENDER: the question is recorded and queued, the email keeps waiting
        return StepResult.to(EmailStatus.AWAITING_SENDER, output).withWrites(e -> {
            NoticeEntity n = new NoticeEntity();
            n.kind = NoticeKind.CLARIFICATION;
            n.email = e;
            n.round = round;
            n.recipient = e.sender;
            n.subject = SendDispatcher.replySubject(e.subject);
            n.body = body.get();
            n.createdAt = Instant.now();
            n.persist();
            OutboxEntity.forNotice(n, kind, dispatcher.domain());
        });
    }
}
