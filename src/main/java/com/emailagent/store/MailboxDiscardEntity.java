package com.emailagent.store;

import com.emailagent.model.OutboxKind;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * A draft the owner rejected, to remove from the mailbox's Drafts (design doc §7.6). Written in the same transaction
 * as the rejection, carried out afterwards by the outbox relay. The database accepts only a saved reply draft of the
 * same email and lets nothing change afterwards except the time it was done.
 */
@Entity
@Table(name = "mailbox_discard")
public class MailboxDiscardEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotNull @ManyToOne(fetch = FetchType.LAZY)
    public EmailEntity email;

    /** The outbox row that put the draft in the mailbox: its Message-ID finds the draft again. */
    @NotNull @ManyToOne(fetch = FetchType.LAZY)
    public OutboxEntity outbox;

    @NotNull
    public Instant createdAt;

    public Instant doneAt;

    /**
     * In the current transaction: one discard for every reply draft of this email that went (or is going) to the
     * mailbox as a draft and has none yet. Returns how many were queued.
     */
    public static int queueFor(EmailEntity email) {
        List<OutboxEntity> drafts = OutboxEntity.list("email.id = ?1 and kind = ?2 and draft is not null"
                + " and id not in (select d.outbox.id from MailboxDiscardEntity d where d.email.id = ?1)", email.id,
                OutboxKind.DRAFT);
        for (OutboxEntity o : drafts) {
            MailboxDiscardEntity d = new MailboxDiscardEntity();
            d.email = email;
            d.outbox = o;
            d.createdAt = Instant.now();
            d.persist();
        }
        return drafts.size();
    }
}
