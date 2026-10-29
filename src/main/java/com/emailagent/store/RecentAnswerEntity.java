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
import java.util.List;

/** One question–answer pair from a reply sent recently (design doc §8.4). Shareable slot types only. */
@Entity
@Table(name = "recent_answer")
public class RecentAnswerEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotNull @Enumerated(EnumType.STRING)
    public SlotType slotType;

    @NotBlank @Size(max = 200) public String entityKey;
    @Size(max = 200) public String qualifierKey;

    @NotBlank @Size(max = 100) public String scopeProgram;
    @NotBlank @Size(max = 100) public String scopeTrack;
    @NotBlank @Size(max = 20) public String scopeYear;

    @NotBlank @Size(max = 2000)
    public String value;

    public Long sourceEmailId;

    @NotNull
    public Instant answeredAt;

    /** The sent reply it was read from; null for entries made another way. */
    @Size(max = 998)
    public String sourceReplyId;

    /** Set when a later owner reply gave another value: the entry is no longer used. */
    public Instant supersededAt;

    /** Answers to exactly this key and scope since {@code since}, newest first. */
    public static List<RecentAnswerEntity> find(SlotKey key, SenderScope scope, Instant since) {
        return list("slotType = ?1 and entityKey = ?2 and coalesce(qualifierKey, '') = ?3 and scopeProgram = ?4"
                        + " and scopeTrack = ?5 and scopeYear = ?6 and answeredAt >= ?7 and supersededAt is null"
                        + " order by answeredAt desc",
                key.type(), key.entity(), key.qualifier() == null ? "" : key.qualifier(),
                scope.program(), scope.track(), scope.year(), since);
    }
}
