package com.emailagent.store;

import com.emailagent.model.SenderScope;
import com.emailagent.model.SlotKey;
import com.emailagent.model.SlotType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Optional;

/** A scoped answer confirmed by the owner, reusable for later senders in the same scope (design doc §8). */
@Entity
@Table(name = "fact")
public class FactEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** The agent whose context this fact belongs to: it is never used for another agent's emails. */
    @NotBlank @Size(max = 50)
    public String agentId;

    @NotNull @Enumerated(EnumType.STRING)
    public SlotType slotType;

    @NotBlank @Size(max = 200) public String entityKey;
    @Size(max = 200) public String qualifierKey;

    @NotBlank @Size(max = 100) public String scopeProgram;
    @NotBlank @Size(max = 100) public String scopeTrack;
    @NotBlank @Size(max = 20) public String scopeYear;

    @NotBlank @Size(max = 2000)
    public String value;

    /** Can be given to other senders. False for anything personal; the database refuses true on personal types. */
    public boolean shareable;

    /** After this instant the fact is never used (a date that has passed, a semester that ended). */
    public Instant validUntil;

    /** Who confirmed it ("card:7 answered by owner"). */
    @NotBlank @Size(max = 500)
    public String source;

    public int factVersion = 1;

    /** The card whose answer produced it; null for a fact entered another way. */
    public Long requestId;

    /** The version this one replaced: the chain lets us find who received an outdated value. */
    public Long previousFactId;

    /** When this value was announced to its whole cohort: a broadcast is not suggested twice (design doc §7.7). */
    public Instant broadcastAt;

    public boolean active = true;

    @NotNull
    public Instant createdAt;

    public SlotKey key() {
        return new SlotKey(slotType, entityKey, qualifierKey);
    }

    /** The active fact of this agent for exactly this key and exactly this scope, if any. */
    public static Optional<FactEntity> findActive(String agentId, SlotKey key, SenderScope scope) {
        return find("active = true and agentId = ?1 and slotType = ?2 and entityKey = ?3"
                        + " and coalesce(qualifierKey, '') = ?4 and scopeProgram = ?5 and scopeTrack = ?6"
                        + " and scopeYear = ?7",
                agentId, key.type(), key.entity(), key.qualifier() == null ? "" : key.qualifier(),
                scope.program(), scope.track(), scope.year())
                .firstResultOptional();
    }
}
