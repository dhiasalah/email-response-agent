package com.emailagent.store;

import com.emailagent.model.Author;
import com.emailagent.model.Language;
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

/** A reply written for an email (design doc §4.4). Only the orchestrator sends it, after the reviewer. */
@Entity
@Table(name = "reply_draft")
public class DraftEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public EmailEntity email;

    @NotBlank @Size(max = 6000)
    public String body;

    @NotNull @Enumerated(EnumType.STRING)
    public Language language;

    /** "slot 12 = fact:3:v1; slot 13 = kb:kb1 …": the grounding the reviewer checks. */
    @NotBlank @Size(max = 2000)
    public String factsUsed;

    @Size(max = 100)
    public String modelVersion;

    public int attempts;

    /** False once a newer draft replaced it (rewrite after an edit); kept for the audit. */
    public boolean current = true;

    /** WRITER, or OWNER when the owner edited or wrote the reply in the panel. */
    @NotNull @Enumerated(EnumType.STRING)
    public Author writtenBy = Author.WRITER;

    @NotNull
    public Instant createdAt;

    public static Optional<DraftEntity> currentFor(long emailId) {
        return find("email.id = ?1 and current = true", emailId).firstResultOptional();
    }
}
