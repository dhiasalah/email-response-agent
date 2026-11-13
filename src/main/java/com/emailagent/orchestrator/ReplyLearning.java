package com.emailagent.orchestrator;

import com.emailagent.agents.CuratorAgent;
import com.emailagent.llm.LlmException;
import com.emailagent.mailbox.Mailbox;
import com.emailagent.mailbox.SentMessage;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.SenderScope;
import com.emailagent.model.SlotKey;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.ProcessedReplyEntity;
import com.emailagent.store.RecentAnswerEntity;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;

/**
 * Feeds the 7-day recent answer log from replies the owner sent by hand (design doc §8.4, §16.8). Same pattern as
 * the other steps: read in a short transaction, the model outside it, then one commit that re-checks.
 *
 * <p>Replies the agents send are not logged back: their values already come from memory or from a card where the
 * owner chose the audience, and re-logging them would let memory confirm itself ("answered twice").
 */
@ApplicationScoped
public class ReplyLearning {

    public record Result(int replies, int pairs) {}

    private record Context(long emailId, String agent, SenderScope scope, List<CuratorAgent.Question> questions) {}

    private final Mailbox mailbox;
    private final CuratorAgent curator;

    public ReplyLearning(Mailbox mailbox, CuratorAgent curator) {
        this.mailbox = mailbox;
        this.curator = curator;
    }

    public Result learnFromSent() {
        return learn(mailbox.fetchSent(), curator);
    }

    /** Same as {@link #learnFromSent()} with explicit replies and agent (tests with a scripted model). */
    public Result learn(List<SentMessage> sent, CuratorAgent curatorAgent) {
        int replies = 0, pairs = 0;
        for (SentMessage m : sent) {
            if (m.messageId() == null || m.inReplyTo() == null) {
                continue;
            }
            Instant now = Instant.now();
            Context ctx = QuarkusTransaction.requiringNew().call(() -> context(m, now));
            if (ctx == null) {
                continue;
            }
            List<CuratorAgent.Learned> learned;
            try {
                learned = curatorAgent.extract(ctx.questions(), m.bodyText());
            } catch (LlmException e) {
                Log.warnf("curator: could not read sent reply for email %d (%s), will retry", ctx.emailId(), e.reason());
                continue; // not marked processed: the next run tries again
            }
            Integer saved = QuarkusTransaction.requiringNew().call(() -> commit(m, ctx, learned, curatorAgent, now));
            if (saved != null) {
                replies++;
                pairs += saved;
            }
        }
        return new Result(replies, pairs);
    }

    /** Null when there is nothing to ask the model now (already read, nothing learnable, or not triaged yet). */
    private static Context context(SentMessage m, Instant now) {
        if (ProcessedReplyEntity.findById(m.messageId()) != null) {
            return null;
        }
        EmailEntity email = EmailEntity.findByMessageId(m.inReplyTo()).orElse(null);
        if (email == null) {
            ProcessedReplyEntity.mark(m.messageId(), null, 0, now); // a reply to something we never received
            return null;
        }
        if (email.slots.isEmpty()) {
            if (email.status == EmailStatus.NEW) {
                return null; // triage has not run yet: come back once the questions are known
            }
            ProcessedReplyEntity.mark(m.messageId(), email.id, 0, now);
            return null;
        }
        if (email.agentId == null) {
            if (email.status == EmailStatus.TRIAGED) {
                return null; // the router has not run yet: come back once the agent is known
            }
            ProcessedReplyEntity.mark(m.messageId(), email.id, 0, now); // never routed: no agent memory to feed
            return null;
        }
        if (!email.scope().isKnown()) {
            ProcessedReplyEntity.mark(m.messageId(), email.id, 0, now); // no audience: nothing reusable
            return null;
        }
        List<CuratorAgent.Question> questions = email.slots.stream()
                .map(s -> new CuratorAgent.Question(s.id, s.type, s.entity, s.qualifier, s.question)).toList();
        return new Context(email.id, email.agentId, email.scope(), questions);
    }

    private static Integer commit(SentMessage m, Context ctx, List<CuratorAgent.Learned> learned,
            CuratorAgent curatorAgent, Instant now) {
        if (ProcessedReplyEntity.findById(m.messageId()) != null) {
            return null; // another run got there first
        }
        Instant at = m.sentAt() == null || m.sentAt().isAfter(now) ? now : m.sentAt();
        for (CuratorAgent.Learned l : learned) {
            SlotKey key = l.key();
            // the owner's newest word wins: older entries with another value stop being used (design doc §8.4)
            RecentAnswerEntity.<RecentAnswerEntity>list("agentId = ?1 and slotType = ?2 and entityKey = ?3"
                            + " and coalesce(qualifierKey, '') = ?4 and scopeProgram = ?5 and scopeTrack = ?6"
                            + " and scopeYear = ?7 and supersededAt is null",
                    ctx.agent(), key.type(), key.entity(), key.qualifier() == null ? "" : key.qualifier(),
                    ctx.scope().program(), ctx.scope().track(), ctx.scope().year()).stream()
                    .filter(r -> !SlotKey.normalize(r.value).equals(SlotKey.normalize(l.value())))
                    .filter(r -> !r.answeredAt.isAfter(at))
                    .forEach(r -> r.supersededAt = now);

            RecentAnswerEntity r = new RecentAnswerEntity();
            r.agentId = ctx.agent();
            r.slotType = key.type();
            r.entityKey = key.entity();
            r.qualifierKey = key.qualifier();
            r.scopeProgram = ctx.scope().program();
            r.scopeTrack = ctx.scope().track();
            r.scopeYear = ctx.scope().year();
            r.value = l.value();
            r.sourceEmailId = ctx.emailId();
            r.sourceReplyId = m.messageId();
            r.answeredAt = at;
            r.persist();
        }
        ProcessedReplyEntity.mark(m.messageId(), ctx.emailId(), learned.size(), now);
        DecisionLogEntry entry = new DecisionLogEntry();
        entry.emailId = ctx.emailId();
        entry.agent = "curator";
        entry.inputSummary = "sent reply to email " + ctx.emailId();
        entry.output = "{\"learned\":" + learned.size() + ",\"slots\":"
                + learned.stream().map(l -> String.valueOf(l.slotId())).toList() + "}";
        entry.modelVersion = curatorAgent.modelVersion();
        entry.createdAt = now;
        entry.persist();
        return learned.size();
    }
}
