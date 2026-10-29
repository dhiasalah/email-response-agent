package com.emailagent.store;

import com.emailagent.model.NoticeKind;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Optional;

/**
 * A message written by code from a template (design doc §6, §7.5, §7.7). The database keeps the text as written,
 * checks that an email's follow-up goes to that email's sender, and records approval or answer once.
 */
@Entity
@Table(name = "notice")
public class NoticeEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotNull @Enumerated(EnumType.STRING)
    public NoticeKind kind;

    /** The email it follows up; null for a broadcast. */
    @ManyToOne(fetch = FetchType.LAZY)
    public EmailEntity email;

    /** The card a broadcast answers; null otherwise. */
    @ManyToOne(fetch = FetchType.LAZY)
    public InputRequestEntity request;

    /** Clarification round, 1 or 2. */
    public Integer round;

    @NotBlank @Size(max = 320)
    public String recipient;

    @NotNull @Size(max = 998)
    public String subject;

    @NotBlank @Size(max = 6000)
    public String body;

    @NotNull
    public Instant createdAt;

    /** A broadcast leaves only once the owner approved it. */
    public Instant approvedAt;

    /** A clarification the sender answered. */
    public Instant answeredAt;

    public static Optional<NoticeEntity> latestClarification(long emailId) {
        return find("email.id = ?1 and kind = ?2 order by round desc", emailId, NoticeKind.CLARIFICATION)
                .firstResultOptional();
    }
}
