package com.emailagent.store;

import com.emailagent.model.Author;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Optional;
import org.hibernate.annotations.Immutable;

/**
 * The reviewer's verdict on one draft (design doc §4.5, §9). Insert-only: a new draft gets a new review. Only codes
 * are stored (rule names, issue enums), never the model's words.
 */
@Entity
@Immutable
@Table(name = "reply_review")
public class ReviewEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public EmailEntity email;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public DraftEntity draft;

    public boolean passed;

    /** "unsupported_link,blocked_topic": the deterministic rules that failed. Null when none did. */
    @Size(max = 500)
    public String ruleFailures;

    /** "NEW_COMMITMENT": what the model reported. Null when nothing (or when the rules stopped before the model). */
    @Size(max = 500)
    public String modelIssues;

    /** "writer_retry,suspicious_input": shown on its own, never sent in bulk or automatically. */
    @Size(max = 500)
    public String outlierReasons;

    @Size(max = 100)
    public String modelVersion;

    /** REVIEWER, or OWNER: the owner approved a reply they wrote or edited themselves (V1701 checks it). */
    @NotNull @Enumerated(EnumType.STRING)
    public Author reviewedBy = Author.REVIEWER;

    @NotNull
    public Instant createdAt;

    public static Optional<ReviewEntity> latestFor(long emailId) {
        return find("email.id = ?1 order by id desc", emailId).firstResultOptional();
    }
}
