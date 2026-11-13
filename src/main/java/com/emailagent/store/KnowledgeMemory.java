package com.emailagent.store;

import com.emailagent.model.SlotKey;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Writes facts (design doc §8.2): one active version per slot key and scope, older versions kept, and the list of
 * emails that received a value that has just changed. Call inside a transaction.
 */
@ApplicationScoped
public class KnowledgeMemory {

    /** {@code previous} is the version replaced (null if none or unchanged); {@code toCorrect} got its value. */
    public record Written(FactEntity fact, FactEntity previous, List<Long> toCorrect) {}

    public Written write(FactDecision d, Instant now) {
        Optional<FactEntity> current = FactEntity.findActive(d.key(), d.scope());
        if (current.isPresent() && SlotKey.normalize(current.get().value).equals(SlotKey.normalize(d.value()))) {
            FactEntity same = current.get();
            if (d.validUntil() != null && (same.validUntil == null || d.validUntil().isAfter(same.validUntil))) {
                same.validUntil = d.validUntil(); // confirmed again: keep it, don't fork a version
            }
            return new Written(same, null, List.of());
        }

        FactEntity f = new FactEntity();
        f.slotType = d.key().type();
        f.entityKey = d.key().entity();
        f.qualifierKey = d.key().qualifier();
        f.scopeProgram = d.scope().program();
        f.scopeTrack = d.scope().track();
        f.scopeYear = d.scope().year();
        f.value = d.value();
        f.shareable = true;
        f.validUntil = d.validUntil();
        f.source = d.source();
        f.requestId = d.requestId();
        f.createdAt = now;

        FactEntity previous = null;
        List<Long> toCorrect = List.of();
        if (current.isPresent()) {
            previous = current.get();
            previous.active = false;
            FactEntity.flush(); // the partial unique index allows one active row: retire before inserting
            f.factVersion = previous.factVersion + 1;
            f.previousFactId = previous.id;
            toCorrect = recipients(previous, d.requestId());
        }
        f.persist();
        return new Written(f, previous, toCorrect);
    }

    /** The owner retires a fact ("What the agent knows" view): it is never used again, the row stays for audit. */
    public void retire(long factId) {
        FactEntity f = FactEntity.findById(factId);
        if (f != null) {
            f.active = false;
        }
    }

    /**
     * Emails whose slot got the old value: filled from that fact version, or from the card that produced it.
     * The emails of the card being answered now are excluded: they get the new value.
     */
    static List<Long> recipients(FactEntity old, Long currentRequestId) {
        String fromFact = "fact:" + old.id + ":%";
        String fromCard = old.requestId == null ? "-" : "card:" + old.requestId + " %";
        return SlotEntity.getEntityManager().createQuery(
                        "select distinct s.email.id from SlotEntity s where (s.source like ?1 or s.source like ?2)"
                                + " and (s.requestId is null or s.requestId <> ?3) order by 1", Long.class)
                .setParameter(1, fromFact).setParameter(2, fromCard)
                .setParameter(3, currentRequestId == null ? -1L : currentRequestId)
                .getResultList();
    }
}
