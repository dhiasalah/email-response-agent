package com.emailagent.store;

import com.emailagent.model.ClusterStatus;
import com.emailagent.model.FieldType;
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
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A cluster of questions waiting on the same input, shown to the owner as one card (design doc §5, §7.2, §7.3).
 * Its slot key and scope are the shared input; the centroid lets later questions find it.
 */
@Entity
@Table(name = "input_request")
public class InputRequestEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotNull @Enumerated(EnumType.STRING)
    public SlotType slotType;

    @NotBlank @Size(max = 200) public String entityKey;
    @Size(max = 200) public String qualifierKey;

    @Size(max = 100) public String scopeProgram;
    @Size(max = 100) public String scopeTrack;
    @Size(max = 20) public String scopeYear;

    /** One sender's own question: answers that email only, nobody else may join (the database checks it too). */
    public boolean personal;

    /** The card question, written by code from the slot key: no model text on the owner's screen. */
    @NotBlank @Size(max = 500)
    public String question;

    @NotNull @Enumerated(EnumType.STRING)
    public FieldType fieldType;

    /** The first member's question as the sender asked it (untrusted): what the verifier compares new questions to. */
    @Size(max = 500)
    public String sampleQuestion;

    /** Mean embedding of the members. Null on personal cards: they are never matched. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    public float[] centroid;

    @Size(max = 100)
    public String embeddingModel;

    public int memberCount;

    /** A value found by the resolver (calendar, old fact, recent answers) that the owner only has to confirm. */
    @Size(max = 2000) public String proposedValue;
    @Size(max = 500) public String proposalSource;

    /** 0..1, higher first (design doc §7.4). Recomputed whenever the card changes, and on each run for age. */
    public double priority;

    @NotNull @Enumerated(EnumType.STRING)
    public ClusterStatus status = ClusterStatus.OPEN;

    @NotNull public Instant createdAt;
    @NotNull public Instant lastActivity;

    @Version
    public long version;

    public SlotKey key() {
        return new SlotKey(slotType, entityKey, qualifierKey);
    }

    public SenderScope scope() {
        return new SenderScope(scopeProgram, scopeTrack, scopeYear);
    }

    /** Cards a new question may join. */
    public static List<InputRequestEntity> listJoinable() {
        return list("status in ?1 and personal = false order by id", List.of(ClusterStatus.OPEN, ClusterStatus.WAITING_INPUT));
    }

    /** Every card still in the owner's queue, highest priority first. */
    public static List<InputRequestEntity> listQueue() {
        return list("status in ?1 order by priority desc, id", List.of(ClusterStatus.OPEN, ClusterStatus.WAITING_INPUT));
    }
}
