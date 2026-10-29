package com.emailagent.orchestrator;

import com.emailagent.agents.GroupingAgent;
import com.emailagent.agents.WriterAgent;
import com.emailagent.config.AppConfig;
import com.emailagent.mailbox.CohortDirectory;
import com.emailagent.model.ClusterStatus;
import com.emailagent.model.Language;
import com.emailagent.model.NoticeKind;
import com.emailagent.model.OutboxKind;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.FactEntity;
import com.emailagent.store.InputRequestEntity;
import com.emailagent.store.NoticeEntity;
import com.emailagent.store.OutboxEntity;
import com.emailagent.store.SlotEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Broadcast suggestions (design doc §7.7): when many people of one cohort asked what a card answered, one
 * announcement to the cohort's list replaces the wave. Detection and text are code; sending is never automatic:
 * {@link #approve} is the owner's action, and the database refuses a broadcast in the outbox without it.
 */
@ApplicationScoped
public class BroadcastRun {

    private final CohortDirectory cohorts;
    private final SendDispatcher dispatcher;
    private final AppConfig config;

    public BroadcastRun(CohortDirectory cohorts, SendDispatcher dispatcher, AppConfig config) {
        this.cohorts = cohorts;
        this.dispatcher = dispatcher;
        this.config = config;
    }

    /** What the trigger needs to know about one answered card. */
    public record Candidate(boolean personal, boolean shareableFact, boolean alreadyBroadcast, long recentQuestions,
            boolean hasList) {}

    /** Design doc §7.7 trigger: a group scope (not one person), a shareable answer, enough recent questions. */
    public static boolean eligible(Candidate c, int minQuestions) {
        return !c.personal() && c.shareableFact() && !c.alreadyBroadcast() && c.hasList()
                && c.recentQuestions() >= minQuestions;
    }

    /** Creates a suggestion (not approved) for every eligible answered card. Returns how many. */
    public int suggest() {
        Instant since = Instant.now().minus(Duration.ofDays(config.broadcastWindowDays()));
        return QuarkusTransaction.requiringNew().call(() -> {
            int suggested = 0;
            List<InputRequestEntity> cards = InputRequestEntity.list(
                    "from InputRequestEntity r where r.status = ?1 and r.personal = false and not exists"
                            + " (select n.id from NoticeEntity n where n.request = r and n.kind = ?2) order by r.id",
                    ClusterStatus.ANSWERED, NoticeKind.BROADCAST);
            for (InputRequestEntity card : cards) {
                Optional<FactEntity> fact = FactEntity.find("requestId = ?1 and active = true", card.id)
                        .firstResultOptional();
                Optional<String> list = cohorts.listFor(card.scope());
                long recent = SlotEntity.count("requestId = ?1 and email.receivedAt > ?2", card.id, since);
                Candidate c = new Candidate(card.personal, fact.map(f -> f.shareable).orElse(false),
                        fact.map(f -> f.broadcastAt != null).orElse(false), recent, list.isPresent());
                if (!eligible(c, config.broadcastMinQuestions())) {
                    continue;
                }
                FactEntity f = fact.get();
                String label = GroupingAgent.label(card.slotType);
                String topic = NoticeTemplates.topic(card.entityKey);
                NoticeEntity n = new NoticeEntity();
                n.kind = NoticeKind.BROADCAST;
                n.request = card;
                n.recipient = list.get();
                n.subject = NoticeTemplates.broadcastSubject(label, topic);
                n.body = NoticeTemplates.broadcastBody(card.scope(), label, topic,
                        WriterAgent.display(card.fieldType, f.value, Language.EN),
                        WriterAgent.display(card.fieldType, f.value, Language.FR), config.ownerName());
                n.createdAt = Instant.now();
                n.persist();
                DecisionLogEntry.record(null, "broadcast", "card " + card.id,
                        "{\"suggested\":" + n.id + ",\"questions\":" + recent + "}");
                suggested++;
            }
            return suggested;
        });
    }

    /**
     * The owner approved this exact text and recipient (the panel calls this). Queues it, marks the fact as
     * announced. False if it isn't a pending broadcast.
     */
    public boolean approve(long noticeId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            NoticeEntity n = NoticeEntity.findById(noticeId);
            if (n == null || n.kind != NoticeKind.BROADCAST || n.approvedAt != null) {
                return false;
            }
            n.approvedAt = Instant.now();
            NoticeEntity.flush(); // the outbox trigger reads the approval
            OutboxEntity.forNotice(n, OutboxKind.SEND, dispatcher.domain());
            FactEntity.<FactEntity>find("requestId = ?1 and active = true", n.request.id).firstResultOptional()
                    .ifPresent(f -> f.broadcastAt = n.approvedAt);
            DecisionLogEntry.record(null, "broadcast", "notice " + n.id, "{\"approved\":" + n.id + "}");
            return true;
        });
    }
}
