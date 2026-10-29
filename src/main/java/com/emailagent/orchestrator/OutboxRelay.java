package com.emailagent.orchestrator;

import com.emailagent.config.AppConfig;
import com.emailagent.mailbox.Mailbox;
import com.emailagent.mailbox.OutgoingMessage;
import com.emailagent.store.DecisionLogEntry;
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

    private record Pending(long id, long emailId, OutgoingMessage message) {}

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
                        .map(o -> new Pending(o.id, o.email.id, new OutgoingMessage(o.kind, o.recipient, o.subject,
                                o.body, o.inReplyTo, o.messageId)))
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
                    DecisionLogEntry.record(p.emailId(), "outbox", "email " + p.emailId() + " outbox " + p.id(),
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
}
