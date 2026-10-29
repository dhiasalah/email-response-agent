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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * A reply or a notice on its way out (design doc §9, §11): written in the same transaction as the decision, delivered
 * to the mailbox afterwards. The database checks recipient, body and review (or notice) on insert, allows one sent
 * reply per email, and lets nothing change afterwards except the delivery time.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** The email answered; null for a broadcast. */
    @ManyToOne(fetch = FetchType.LAZY)
    public EmailEntity email;

    /** A reviewed reply has a draft and its review; a notice has neither. */
    @ManyToOne(fetch = FetchType.LAZY)
    public DraftEntity draft;

    @ManyToOne(fetch = FetchType.LAZY)
    public ReviewEntity review;

    /** Set when this row carries a notice (clarification, holding reply, broadcast) instead of a reply. */
    @ManyToOne(fetch = FetchType.LAZY)
    public NoticeEntity notice;

    @NotNull @Enumerated(EnumType.STRING)
    public OutboxKind kind;

    /** The email's own sender, or the cohort list of a broadcast, copied by code (and checked by the database). */
    @NotBlank @Size(max = 320)
    public String recipient;

    @NotNull @Size(max = 998)
    public String subject;

    @NotBlank @Size(max = 6000)
    public String body;

    /** The answered email's Message-ID; null for a broadcast. */
    @Size(max = 998)
    public String inReplyTo;

    /** Ours, derived from the email and draft: a second delivery of the same row carries the same id. */
    @NotBlank @Size(max = 998)
    public String messageId;

    @NotNull
    public Instant createdAt;

    public Instant deliveredAt;

    /** A notice's outbox row, in the current transaction: recipient, subject and body exactly as the notice holds them. */
    public static OutboxEntity forNotice(NoticeEntity n, OutboxKind kind, String domain) {
        OutboxEntity o = new OutboxEntity();
        o.email = n.email;
        o.notice = n;
        o.kind = kind;
        o.recipient = n.recipient;
        o.subject = n.subject;
        o.body = n.body;
        o.inReplyTo = n.email == null ? null : n.email.messageId;
        o.messageId = "<notice-" + n.id + "@" + domain + ">";
        o.createdAt = Instant.now();
        o.persist();
        return o;
    }
}
