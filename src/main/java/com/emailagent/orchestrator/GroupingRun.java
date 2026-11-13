package com.emailagent.orchestrator;

import com.emailagent.agents.CardPriority;
import com.emailagent.agents.GroupingAgent.Assignment;
import com.emailagent.agents.GroupingAgent.Draft;
import com.emailagent.agents.GroupingAgent.OpenCluster;
import com.emailagent.agents.GroupingAgent.PendingSlot;
import com.emailagent.agents.GroupingAgent;
import com.emailagent.config.AppConfig;
import com.emailagent.mailbox.SenderDirectory;
import com.emailagent.model.ClusterStatus;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.GroupedBy;
import com.emailagent.model.SlotStatus;
import com.emailagent.step.AgentStep;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.InputRequestEntity;
import com.emailagent.store.SlotEntity;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The orchestrator's grouping step (design doc §7.2, §16.5). Grouping works on slots, not on one email's status,
 * so it is not an {@link AgentStep}; it follows the same pattern: snapshot in a short transaction, the agent's slow
 * work (embeddings, verifier) outside any transaction, then one commit that re-checks everything it relies on.
 */
@ApplicationScoped
public class GroupingRun {

    public record Result(int assigned, int newCards, int verifierCalls, int closed) {}

    private final GroupingAgent agent;
    private final SenderDirectory directory;
    private final int batchSize;
    private final Duration expiry;
    private final CardPriority.Weights weights;

    public GroupingRun(GroupingAgent agent, SenderDirectory directory, AppConfig config) {
        this.agent = agent;
        this.directory = directory;
        this.batchSize = config.groupingBatchSize();
        this.expiry = Duration.ofDays(config.clusterExpiryDays());
        this.weights = CardPriority.Weights.of(config);
    }

    public Result run() {
        return run(agent);
    }

    /** Same as {@link #run()} with an explicit agent (tests with a scripted model and embedder). */
    public Result run(GroupingAgent groupingAgent) {
        Instant now = Instant.now();
        List<PendingSlot> pending = QuarkusTransaction.requiringNew().call(this::pendingSlots);
        List<OpenCluster> open = QuarkusTransaction.requiringNew().call(GroupingRun::joinableCards);

        int assigned = 0, created = 0, calls = 0;
        if (!pending.isEmpty()) {
            GroupingAgent.Plan plan = groupingAgent.plan(pending, open);
            calls = plan.verifierCalls();
            try {
                int[] counts = QuarkusTransaction.requiringNew().call(() -> commit(plan, groupingAgent, now));
                assigned = counts[0];
                created = counts[1];
            } catch (RuntimeException e) {
                // the database refused part of the plan (a guard fired, a concurrent change): nothing is applied,
                // the slots stay unassigned and the next run plans again
                Log.warnf("grouping plan not applied (%s)", e.getClass().getSimpleName());
            }
        }
        int closed = QuarkusTransaction.requiringNew().call(() -> refreshQueue(now));
        return new Result(assigned, created, calls, closed);
    }

    /** Questions waiting for the owner and on no card yet: the newest {@code batchSize}, handled oldest first. */
    private List<PendingSlot> pendingSlots() {
        List<SlotEntity> slots = SlotEntity.<SlotEntity>find(
                "select s from SlotEntity s join fetch s.email e where e.status = ?1 and s.requestId is null"
                        + " and s.status in ?2 order by e.receivedAt desc, s.id desc",
                EmailStatus.AWAITING_INPUT, List.of(SlotStatus.UNRESOLVED, SlotStatus.PROPOSED))
                .page(0, batchSize).list();
        List<PendingSlot> out = new ArrayList<>();
        for (int i = slots.size() - 1; i >= 0; i--) {
            SlotEntity s = slots.get(i);
            out.add(new PendingSlot(s.id, s.email.id, s.email.sender, s.email.receivedAt, s.email.scope(), s.type,
                    s.entity, s.qualifier, s.question, s.status, s.value, s.source));
        }
        return out;
    }

    private static List<OpenCluster> joinableCards() {
        return InputRequestEntity.listJoinable().stream()
                .map(c -> new OpenCluster(c.id, c.version, c.key(), c.scope(), c.centroid, c.memberCount,
                        c.sampleQuestion, c.proposedValue, c.proposalSource, c.embeddingModel))
                .toList();
    }

    /** Applies the plan where its assumptions still hold; anything that moved meanwhile waits for the next run. */
    private int[] commit(GroupingAgent.Plan plan, GroupingAgent groupingAgent, Instant now) {
        Map<Draft, InputRequestEntity> cards = new IdentityHashMap<>();
        Set<Draft> stale = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Draft d : plan.cards()) {
            if (d.id() != null) {
                InputRequestEntity current = InputRequestEntity.findById(d.id());
                if (current == null || current.version != d.version() || !current.status.acceptsMembers()) {
                    stale.add(d); // the owner (or another run) changed the card since the snapshot
                } else {
                    cards.put(d, current);
                }
            }
        }
        int assigned = 0, created = 0;
        for (Assignment a : plan.assignments()) {
            if (stale.contains(a.card())) {
                continue;
            }
            SlotEntity slot = SlotEntity.findById(a.slot().slotId());
            if (slot == null || slot.requestId != null || slot.status != a.slot().status()
                    || slot.email.status != EmailStatus.AWAITING_INPUT) {
                continue;
            }
            InputRequestEntity card = cards.get(a.card());
            if (card == null) {
                card = create(a.card(), now);
                cards.put(a.card(), card);
                created++;
            }
            if (a.embedding() != null && !card.personal) {
                card.centroid = GroupingAgent.mean(card.centroid, card.memberCount, a.embedding());
            }
            card.memberCount++;
            card.lastActivity = now;
            if (slot.status == SlotStatus.PROPOSED && card.proposedValue == null) {
                card.proposedValue = slot.value;
                card.proposalSource = slot.source;
            }
            slot.requestId = card.id;
            slot.groupedBy = a.by();
            boolean modelAsked = a.by() == GroupedBy.VERIFIED || (a.note() != null && a.note().startsWith("verifier"));
            DecisionLogEntry entry = new DecisionLogEntry();
            entry.emailId = slot.email.id;
            entry.agent = "grouping";
            entry.inputSummary = "slot " + slot.id + " -> card " + card.id;
            entry.output = log(a, card.id);
            entry.confidence = a.similarity();
            entry.modelVersion = modelAsked ? groupingAgent.modelVersion() : null;
            entry.createdAt = now;
            entry.persist();
            assigned++;
        }
        return new int[] {assigned, created};
    }

    private static InputRequestEntity create(Draft d, Instant now) {
        InputRequestEntity c = new InputRequestEntity();
        c.slotType = d.key().type();
        c.entityKey = d.key().entity();
        c.qualifierKey = d.key().qualifier();
        c.personal = d.personal();
        if (d.scope().isKnown()) {
            c.scopeProgram = d.scope().program();
            c.scopeTrack = d.scope().track();
            c.scopeYear = d.scope().year();
        }
        c.question = GroupingAgent.cardQuestion(d.key(), d.scope(), d.personal());
        c.fieldType = d.key().type().fieldType();
        c.sampleQuestion = d.sampleQuestion() == null || d.sampleQuestion().length() <= 500 ? d.sampleQuestion()
                : d.sampleQuestion().substring(0, 500);
        c.embeddingModel = d.embeddingModel();
        c.createdAt = now;
        c.lastActivity = now;
        c.persist();
        return c;
    }

    /**
     * Priorities move with time (age), so every queued card is re-scored on each run. An empty card closes only
     * after it expired without activity: its key and centroid keep catching late questions until then (§6.1).
     */
    private int refreshQueue(Instant now) {
        int closed = 0;
        List<InputRequestEntity> cards = InputRequestEntity.list("status in ?1",
                List.of(ClusterStatus.OPEN, ClusterStatus.WAITING_INPUT, ClusterStatus.ANSWERED));
        for (InputRequestEntity card : cards) {
            List<SlotEntity> members = SlotEntity.list("requestId = ?1", card.id);
            List<SlotEntity> waiting = members.stream().filter(s -> s.email.status == EmailStatus.AWAITING_INPUT).toList();
            boolean active = members.stream().anyMatch(s -> !EmailLifecycle.isTerminal(s.email.status));
            if (!active && card.lastActivity.isBefore(now.minus(expiry)) && card.status.canMoveTo(ClusterStatus.CLOSED)) {
                card.status = ClusterStatus.CLOSED;
                closed++;
                continue;
            }
            if (card.status.acceptsMembers()) {
                Set<Long> seen = new HashSet<>();
                List<CardPriority.Member> ranked = waiting.stream().filter(s -> seen.add(s.email.id))
                        .map(s -> new CardPriority.Member(s.email.sender, directory.roleOf(s.email.sender, s.email.sanitizerFlags),
                                s.email.receivedAt, (s.email.subject == null ? "" : s.email.subject) + " "
                                        + (s.question == null ? "" : s.question)))
                        .toList();
                double score = CardPriority.score(ranked, card.proposedValue, now, weights);
                if (score != card.priority) {
                    card.priority = score;
                }
            }
        }
        return closed;
    }

    /** Audit row: ids, the reason and the score; never the question text (it stays in the slot row). */
    private static String log(Assignment a, long cardId) {
        return "{\"slot\":" + a.slot().slotId() + ",\"card\":" + cardId + ",\"by\":\"" + a.by() + "\""
                + (a.similarity() == null ? "" : ",\"similarity\":" + Math.round(a.similarity() * 1000) / 1000.0)
                + (a.note() == null ? "" : ",\"note\":\"" + a.note() + "\"") + "}";
    }
}
