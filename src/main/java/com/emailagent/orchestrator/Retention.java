package com.emailagent.orchestrator;

import com.emailagent.config.AppConfig;
import com.emailagent.model.EmailStatus;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.EmailEntity;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Retention and erasure (design doc §13). A finished email older than the retention period, or every email of a
 * sender who asks to be forgotten, becomes a tombstone: id, status, dates and codes stay (the audit log stays whole,
 * the metrics stay true), the content, the addresses and everything written around it are deleted.
 */
@ApplicationScoped
public class Retention {

    /** Finished emails: nothing will happen to them any more. */
    static final List<EmailStatus> FINISHED = List.of(EmailStatus.SENT, EmailStatus.CLOSED, EmailStatus.IGNORED,
            EmailStatus.FILTERED_OUT);

    private final EntityManager em;
    private final Duration retention;

    public Retention(EntityManager em, AppConfig config) {
        this.em = em;
        this.retention = Duration.ofDays(config.retentionDays());
    }

    @Scheduled(cron = "{emailagent.retention-cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void nightly() {
        int erased = eraseFinishedBefore(Instant.now().minus(retention));
        if (erased > 0) {
            Log.infof("retention: %d emails erased", erased);
        }
    }

    /** Erases finished emails last changed before {@code cutoff}. Returns how many. */
    public int eraseFinishedBefore(Instant cutoff) {
        List<Long> ids = QuarkusTransaction.requiringNew().call(() -> EmailEntity.<EmailEntity>list(
                        "status in ?1 and updatedAt < ?2 and erasedAt is null", FINISHED, cutoff).stream()
                .map(e -> e.id).toList());
        ids.forEach(id -> QuarkusTransaction.requiringNew().run(() -> erase(id, "retention")));
        return ids.size();
    }

    /**
     * The right to erasure (GDPR art. 17), on the owner's action for a sender's request: every email of this address.
     * One still in progress is first handed to a human and closed (ESCALATED → CLOSED, both allowed from any active
     * status), so no agent keeps working on erased content.
     */
    public int eraseSender(String address) {
        List<Long> ids = QuarkusTransaction.requiringNew().call(() -> EmailEntity.<EmailEntity>list(
                        "lower(sender) = ?1 and erasedAt is null", address.toLowerCase(Locale.ROOT)).stream()
                .map(e -> e.id).toList());
        for (long id : ids) {
            QuarkusTransaction.requiringNew().run(() -> {
                EmailEntity e = EmailEntity.findById(id);
                if (!EmailLifecycle.isTerminal(e.status)) {
                    if (e.status != EmailStatus.ESCALATED) {
                        DecisionLogEntry.transition(id, "erasure", e.status, EmailStatus.ESCALATED,
                                "{\"escalated\":\"erasure_request\"}", null, null, null);
                        e.status = EmailStatus.ESCALATED;
                        EmailEntity.flush();
                    }
                    DecisionLogEntry.transition(id, "erasure", EmailStatus.ESCALATED, EmailStatus.CLOSED,
                            "{\"closed\":\"erasure_request\"}", null, null, null);
                    e.status = EmailStatus.CLOSED; // the version bump makes any running step's result stale
                    EmailEntity.flush();
                }
                erase(id, "erasure_request");
            });
        }
        return ids.size();
    }

    /** One email, in the caller's transaction: dependents deleted, the email row reduced to a tombstone. */
    private void erase(long id, String reason) {
        em.createNativeQuery("SET LOCAL emailagent.retention = 'on'").executeUpdate();
        for (String table : List.of("outbox", "notice", "reply_review", "reply_draft", "slot")) {
            em.createNativeQuery("DELETE FROM " + table + " WHERE email_id = ?1").setParameter(1, id).executeUpdate();
        }
        EmailEntity e = EmailEntity.findById(id);
        // the Message-ID becomes a hash: still recognized if the mailbox delivers it again, no longer readable
        em.createNativeQuery("""
                UPDATE email SET body = NULL, subject = NULL, sender_name = NULL, thread_id = NULL,
                    sender = ?1, message_id = ?2, scope_program = NULL, scope_track = NULL, scope_year = NULL,
                    erased_at = now()
                WHERE id = ?3""")
                .setParameter(1, "erased-" + id + "@erased.invalid").setParameter(2, erasedMessageId(e.messageId))
                .setParameter(3, id).executeUpdate();
        DecisionLogEntry.record(id, "retention", "email " + id, "{\"erased\":\"" + reason + "\"}");
    }

    /** The stored form of an erased email's Message-ID; the importer compares new mail against it too. */
    public static String erasedMessageId(String messageId) {
        if (messageId == null || messageId.startsWith("erased:")) {
            return messageId;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(messageId.getBytes(StandardCharsets.UTF_8));
            return "erased:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
