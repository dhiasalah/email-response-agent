package com.emailagent.orchestrator;

import com.emailagent.config.AppConfig;
import com.emailagent.mailbox.Mailbox;
import com.emailagent.mailbox.OutgoingMessage;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.MailboxDiscardEntity;
import com.emailagent.store.OutboxEntity;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;

/**
 * Hands committed outbox rows to the mailbox (transactional outbox). The status change and the outbox row are
 * written together, so a crash can't send a reply the database doesn't know about, nor record one never queued.
 * Delivery is at least once: a crash between delivery and marking retries the row, with the same message id.
 */
@ApplicationScoped
public class OutboxRelay {

    private record Pending(long id, Long emailId, OutgoingMessage message) {}

    private record Discard(long id, long emailId, long outboxId, String messageId) {}

    private final Mailbox mailbox;
    private final int batchSize;

    public OutboxRelay(Mailbox mailbox, AppConfig config) {
        this.mailbox = mailbox;
        this.batchSize = config.outboxBatchSize();
    }

    /** Delivers what is waiting, oldest first. Returns how many rows were delivered. */
    public int deliverPending() {
        List<Pending> pending = QuarkusTransaction.requiringNew().call(() ->
                OutboxEntity.<OutboxEntity>find("deliveredAt is null order by id").page(0, batchSize).list().stream()
                        .map(o -> new Pending(o.id, o.email == null ? null : o.email.id, new OutgoingMessage(o.kind,
                                o.recipient, o.subject, o.body, o.inReplyTo, o.messageId)))
                        .toList());
        int delivered = 0;
        for (Pending p : pending) {
            try {
                mailbox.deliver(p.message()); // outside any transaction: the mailbox may be slow
            } catch (RuntimeException e) {
                Log.warnf("outbox row %d not delivered (%s), retried next run", p.id(), e.getClass().getSimpleName());
                continue;
            }
            boolean marked = QuarkusTransaction.requiringNew().call(() -> {
                int rows = OutboxEntity.update("deliveredAt = ?1 where id = ?2 and deliveredAt is null",
                        Instant.now(), p.id());
                if (rows == 1) {
                    DecisionLogEntry.record(p.emailId(), "outbox", "outbox " + p.id(),
                            "{\"delivered\":\"" + p.message().kind().name().toLowerCase() + "\"}");
                }
                return rows == 1;
            });
            if (marked) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * Removes the drafts the owner rejected from the mailbox, oldest first. Run after {@link #deliverPending()}: a
     * draft rejected before it reached the mailbox waits until it is there, so it can't come back after its removal.
     * At least once, like delivery: a failure is retried next run. Returns how many drafts were removed.
     */
    public int discardRejected() {
        List<Discard> pending = QuarkusTransaction.requiringNew().call(() ->
                MailboxDiscardEntity.<MailboxDiscardEntity>find("doneAt is null and outbox.deliveredAt is not null"
                                + " order by id").page(0, batchSize).list().stream()
                        .map(d -> new Discard(d.id, d.email.id, d.outbox.id, d.outbox.messageId))
                        .toList());
        int discarded = 0;
        for (Discard d : pending) {
            try {
                mailbox.discardDraft(d.messageId()); // outside any transaction, like delivery
            } catch (RuntimeException e) {
                Log.warnf("rejected draft of outbox row %d not removed (%s), retried next run", d.outboxId(),
                        e.getClass().getSimpleName());
                continue;
            }
            boolean marked = QuarkusTransaction.requiringNew().call(() -> {
                int rows = MailboxDiscardEntity.update("doneAt = ?1 where id = ?2 and doneAt is null", Instant.now(),
                        d.id());
                if (rows == 1) {
                    DecisionLogEntry.record(d.emailId(), "outbox", "outbox " + d.outboxId(),
                            "{\"discarded\":\"draft\"}");
                }
                return rows == 1;
            });
            if (marked) {
                discarded++;
            }
        }
        return discarded;
    }
}
