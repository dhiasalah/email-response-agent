package com.emailagent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.emailagent.config.SendMode;
import com.emailagent.mailbox.IncomingEmail;
import com.emailagent.mailbox.MailboxImporter;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.Language;
import com.emailagent.model.NoticeKind;
import com.emailagent.model.OutboxKind;
import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotStatus;
import com.emailagent.model.SlotType;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.DraftEntity;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.NoticeEntity;
import com.emailagent.store.OutboxEntity;
import com.emailagent.store.ReviewEntity;
import com.emailagent.store.SlotEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Retention and erasure (design doc §13): a finished email becomes a tombstone, everything personal around it is
 * deleted, the audit log stays, the mailbox can't bring it back; the guards still refuse deletes outside retention.
 */
@QuarkusTest
class RetentionTest {

    @Inject Retention retention;
    @Inject Orchestrator orchestrator;
    @Inject MailboxImporter importer;
    @Inject EntityManager em;

    @Test
    void aFinishedEmailBecomesATombstoneAndStaysErased() {
        String messageId = "<rt-" + UUID.randomUUID() + "@student.univ.example>";
        long id = sentWithEverything(messageId);
        long logRows = logRows(id);
        long active = active();
        // a year and a bit ago; only this email is that old, so other tests' data isn't touched
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE email SET updated_at = now() - interval '400 days' WHERE id IN (" + id + ", " + active + ")")
                .executeUpdate());

        int erased = retention.eraseFinishedBefore(Instant.now().minus(Duration.ofDays(365)));

        assertThat(erased).isEqualTo(1); // the finished one; the READY one is just as old but still in progress
        EmailEntity e = email(id);
        assertThat(e.status).isEqualTo(EmailStatus.SENT); // what happened stays known
        assertThat(e.body).isNull();
        assertThat(e.subject).isNull();
        assertThat(e.senderName).isNull();
        assertThat(e.sender).isEqualTo("erased-" + id + "@erased.invalid");
        assertThat(e.messageId).startsWith("erased:").doesNotContain("student");
        assertThat(e.erasedAt).isNotNull();
        for (String table : List.of("outbox", "notice", "reply_review", "reply_draft", "slot")) {
            assertThat(count(table, id)).as(table).isZero();
        }
        assertThat(logRows(id)).isEqualTo(logRows + 1); // the audit log is kept, plus the erasure itself
        assertThat(email(active).body).isNotNull(); // an email still in progress is not touched

        // the same message still in the mailbox is recognized and not imported again
        MailboxImporter.ImportResult again = importer.importNew(List.of(new IncomingEmail(messageId, null,
                "lina.benali@student.univ.example", "Lina", "Exam", "When?", null, Instant.now(), Map.of())));
        assertThat(again.duplicates()).isEqualTo(1);
    }

    @Test
    void aSenderCanBeForgottenEvenMidway() {
        String sender = "forget-" + UUID.randomUUID().toString().substring(0, 8) + "@student.univ.example";
        long waiting = create(sender, EmailStatus.AWAITING_INPUT);
        long done = create(sender, EmailStatus.IGNORED);

        assertThat(retention.eraseSender(sender.toUpperCase())).isEqualTo(2);

        assertThat(email(waiting).status).isEqualTo(EmailStatus.CLOSED); // no agent keeps working on it
        assertThat(email(waiting).body).isNull();
        assertThat(email(done).sender).doesNotContain("forget-");
        assertThat(QuarkusTransaction.requiringNew().call(() ->
                EmailEntity.count("lower(sender) = ?1", sender))).isZero();
        assertThat(QuarkusTransaction.requiringNew().call(() -> DecisionLogEntry.count(
                "emailId = ?1 and agent = 'erasure'", waiting))).isEqualTo(2); // escalated, then closed
    }

    @Test
    void outsideRetentionTheGuardsStillRefuseDeletes() {
        long id = sentWithEverything("<rt-" + UUID.randomUUID() + "@test>");
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("DELETE FROM outbox WHERE email_id = " + id).executeUpdate()))
                .hasStackTraceContaining("cannot be changed or deleted");
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("DELETE FROM reply_review WHERE email_id = " + id).executeUpdate()))
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("DELETE FROM decision_log WHERE email_id = " + id).executeUpdate()))
                .hasStackTraceContaining("append-only"); // the audit log is never deleted, not even by retention
    }

    // --- helpers ---

    /** A SENT email with its slot, draft, review, outbox row and a holding notice: everything retention removes. */
    long sentWithEverything(String messageId) {
        long id = QuarkusTransaction.requiringNew().call(() -> {
            EmailEntity e = email(messageId, "rt-" + UUID.randomUUID().toString().substring(0, 8)
                    + "@student.univ.example", EmailStatus.REVIEWED);
            e.persist();
            SlotEntity s = new SlotEntity();
            s.email = e;
            s.type = SlotType.EXAM_DATE;
            s.entity = "ml exam";
            s.owner = SlotOwner.USER;
            s.fill("2027-06-03", "fact:1:v1", SlotStatus.FILLED);
            s.persist();
            DraftEntity d = new DraftEntity();
            d.email = e;
            d.body = "Hello,\n\nThe exam is on 3 June 2027.\n\nBest regards,\nProf. Martin";
            d.language = Language.EN;
            d.factsUsed = "slot = fact:1:v1";
            d.attempts = 1;
            d.createdAt = Instant.now();
            d.persist();
            ReviewEntity r = new ReviewEntity();
            r.email = e;
            r.draft = d;
            r.passed = true;
            r.createdAt = Instant.now();
            r.persist();
            NoticeEntity n = new NoticeEntity();
            n.kind = NoticeKind.HOLDING;
            n.email = e;
            n.recipient = e.sender;
            n.subject = "Re: Exam";
            n.body = "Received.";
            n.createdAt = Instant.now();
            n.persist();
            OutboxEntity.forNotice(n, OutboxKind.DRAFT, "univ.example");
            return e.id;
        });
        orchestrator.runStep(new SendDispatcher(new SendDispatcher.Policy(SendMode.AUTO, Set.of(SlotType.EXAM_DATE),
                1000, 100_000), "prof.martin@univ.example"), id);
        assertThat(email(id).status).isEqualTo(EmailStatus.SENT);
        return id;
    }

    long create(String sender, EmailStatus status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EmailEntity e = email("<rt-" + UUID.randomUUID() + "@test>", sender, status);
            e.persist();
            return e.id;
        });
    }

    long active() {
        return create("rt-active-" + UUID.randomUUID().toString().substring(0, 8) + "@student.univ.example",
                EmailStatus.READY);
    }

    static EmailEntity email(String messageId, String sender, EmailStatus status) {
        EmailEntity e = new EmailEntity();
        e.messageId = messageId;
        e.sender = sender;
        e.senderName = "Lina Benali";
        e.subject = "Exam";
        e.body = "When is the exam? My student number is 2024-1187.";
        e.language = Language.EN;
        e.receivedAt = Instant.now();
        e.status = status;
        e.scopeProgram = "M2";
        e.scopeTrack = "IA";
        e.scopeYear = "2026-2027";
        return e;
    }

    static EmailEntity email(long id) {
        return QuarkusTransaction.requiringNew().call(() -> EmailEntity.<EmailEntity>findById(id));
    }

    long count(String table, long emailId) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM " + table + " WHERE email_id = " + emailId).getSingleResult()).longValue());
    }

    static long logRows(long id) {
        return QuarkusTransaction.requiringNew().call(() -> DecisionLogEntry.count("emailId", id));
    }
}
