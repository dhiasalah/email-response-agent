package com.emailagent.orchestrator;

import com.emailagent.agents.CuratorAgent;
import com.emailagent.agents.CuratorAgent.FactDecision;
import com.emailagent.model.ClusterStatus;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.ScopeChoice;
import com.emailagent.model.SlotStatus;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.InputRequestEntity;
import com.emailagent.store.KnowledgeMemory;
import com.emailagent.store.SlotEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The owner answers a card (design doc §7.3, §16.2): every waiting slot on it is filled, emails whose slots are now
 * all filled become READY (join rule), and the curator decides what the answer becomes for later senders.
 * One transaction: the slots, the status changes, the fact and the audit rows land together or not at all.
 */
@ApplicationScoped
public class CardAnswers {

    public record Result(long cardId, int filledSlots, List<Long> readyEmails, Long factId, Integer factVersion,
            String noFactReason, List<Long> toCorrect) {}

    private final CuratorAgent curator;
    private final KnowledgeMemory memory;

    public CardAnswers(CuratorAgent curator, KnowledgeMemory memory) {
        this.curator = curator;
        this.memory = memory;
    }

    /**
     * {@code expectedVersion} is the card version the owner saw: if a question joined or left since, the answer is
     * refused and the panel reloads (the owner must see who will receive the answer).
     *
     * @throws IllegalArgumentException the value doesn't fit the card's field type, or no such card
     * @throws IllegalStateException the card changed since it was shown, or is already answered or closed
     */
    public Result answer(long cardId, long expectedVersion, String rawValue, ScopeChoice choice) {
        Instant now = Instant.now();
        return QuarkusTransaction.requiringNew().call(() -> {
            InputRequestEntity card = InputRequestEntity.findById(cardId);
            if (card == null) {
                throw new IllegalArgumentException("no such card");
            }
            if (card.version != expectedVersion) {
                throw new IllegalStateException("the card changed since it was shown; reload it");
            }
            if (!card.status.acceptsMembers()) {
                throw new IllegalStateException("the card is already " + card.status);
            }
            String value = CuratorAgent.validateAnswer(card.fieldType, rawValue);
            if (card.status == ClusterStatus.OPEN) {
                card.status = ClusterStatus.WAITING_INPUT; // answering it means it was shown
            }
            card.status = ClusterStatus.ANSWERED;
            card.lastActivity = now;

            String source = "card:" + card.id + " answered by owner";
            Set<EmailEntity> touched = new LinkedHashSet<>();
            int filled = 0;
            for (SlotEntity s : SlotEntity.<SlotEntity>list("requestId = ?1 order by id", card.id)) {
                if (s.email.status == EmailStatus.AWAITING_INPUT && s.status != SlotStatus.FILLED) {
                    s.fill(value, source, SlotStatus.FILLED);
                    touched.add(s.email);
                    filled++;
                }
            }
            List<Long> ready = new ArrayList<>();
            for (EmailEntity e : touched) {
                boolean complete = e.slots.stream().allMatch(s -> s.status == SlotStatus.FILLED);
                if (complete && EmailLifecycle.allowed(e.status, EmailStatus.READY)) {
                    DecisionLogEntry.transition(e.id, "owner", e.status, EmailStatus.READY,
                            "{\"card\":" + card.id + "}", null, source, null);
                    e.status = EmailStatus.READY;
                    ready.add(e.id);
                }
            }

            FactDecision decision = curator.decide(new CuratorAgent.Card(card.id, card.key(), card.scope(),
                    card.personal, card.fieldType), value, choice, now);
            Long factId = null;
            Integer factVersion = null;
            List<Long> toCorrect = List.of();
            if (decision.store()) {
                KnowledgeMemory.Written w = memory.write(decision, now);
                factId = w.fact().id;
                factVersion = w.fact().factVersion;
                toCorrect = w.toCorrect();
                for (Long emailId : toCorrect) {
                    // who received the old value: the correction flow (design doc §8.2) starts from these rows
                    DecisionLogEntry.record(emailId, "curator", "fact " + w.previous().id + " replaced by " + factId,
                            "{\"correction_needed\":true,\"old_fact\":" + w.previous().id + ",\"new_fact\":" + factId + "}");
                }
            }
            // the value itself stays in the slot and fact rows; the log says what happened, not what was said
            DecisionLogEntry.record(null, "curator", "card " + card.id + " answered",
                    "{\"card\":" + card.id + ",\"filled\":" + filled + ",\"ready\":" + ready.size()
                            + (factId == null ? ",\"fact\":null,\"reason\":\"" + decision.reason() + "\""
                                    : ",\"fact\":" + factId + ",\"version\":" + factVersion)
                            + ",\"corrections\":" + toCorrect.size() + "}");
            return new Result(card.id, filled, ready, factId, factVersion, decision.reason(), toCorrect);
        });
    }
}
