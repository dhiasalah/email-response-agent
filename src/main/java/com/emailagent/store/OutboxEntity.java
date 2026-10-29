package com.emailagent.store;

import com.emailagent.model.OutboxKind;
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
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * A reply on its way out (design doc §9, §11): written in the same transaction as the status change, delivered to
 * the mailbox afterwards. The database checks the recipient, the body and the review on insert, allows one sent
 * reply per email, and lets nothing change afterwards except the delivery time.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public EmailEntity email;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public DraftEntity draft;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public ReviewEntity review;

    @NotNull @Enumerated(EnumType.STRING)
    public OutboxKind kind;

    /** Always the email's own sender, copied by code (and checked by the database). */
    @NotBlank @Email @Size(max = 320)
    public String recipient;

    @NotNull @Size(max = 998)
    public String subject;

    @NotBlank @Size(max = 6000)
    public String body;

    @NotBlank @Size(max = 998)
    public String inReplyTo;

    /** Ours, derived from the email and draft: a second delivery of the same row carries the same id. */
    @NotBlank @Size(max = 998)
    public String messageId;

    @NotNull
    public Instant createdAt;

    public Instant deliveredAt;
}
