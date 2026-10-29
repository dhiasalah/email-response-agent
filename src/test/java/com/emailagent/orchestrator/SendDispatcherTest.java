package com.emailagent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.emailagent.config.SendMode;
import com.emailagent.mailbox.MockMailbox;
import com.emailagent.mailbox.OutgoingMessage;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.Language;
import com.emailagent.model.OutboxKind;
import com.emailagent.model.SenderScope;
import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotStatus;
import com.emailagent.model.SlotType;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.DraftEntity;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.OutboxEntity;
import com.emailagent.store.ReviewEntity;
import com.emailagent.store.SlotEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Send modes and the outbox (design doc §9, §11): draft unless every condition holds, one reviewed reply to the
 * original sender, at most once, and a database that refuses anything else even from code that skips these checks.
 */
@QuarkusTest
class SendDispatcherTest {

    static final SendDispatcher.Policy AUTO = new SendDispatcher.Policy(SendMode.AUTO,
            Set.of(SlotType.EXAM_DATE, SlotType.EXAM_ROOM), 100, 10_000);

    @Inject Orchestrator orchestrator;
    @Inject SendDispatcher configured;
    @Inject OutboxRelay relay;
    @Inject MockMailbox mailbox;
    @Inject EntityManager em;

    @Test
    void theDefaultConfigurationSavesADraftAndTheRelayDeliversItOnce() {
        long id = reviewed(sender(), "Exam date\r\nBcc: everyone@univ.example", true, null, SlotType.EXAM_DATE);

        orchestrator.runStep(configured, id);
        relay.deliverPending();
        relay.deliverPending(); // a second run (restart, next tick) delivers nothing again

        assertThat(status(id)).isEqualTo(EmailStatus.DRAFT_SAVED);
        assertThat(lastLog(id, "dispatcher")).contains("send_mode_draft");
        OutboxEntity o = outbox(id);
        assertThat(o.kind).isEqualTo(OutboxKind.DRAFT);
        assertThat(o.deliveredAt).isNotNull();
        // the sender wrote the subject: the header injection is flattened onto one line
        assertThat(o.subject).isEqualTo("Re: Exam date Bcc: everyone@univ.example");
        List<OutgoingMessage> out = delivered(o.messageId);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).to()).isEqualTo(o.recipient);
        assertThat(QuarkusTransaction.requiringNew().call(() ->
                DecisionLogEntry.count("emailId = ?1 and agent = 'outbox'", id))).isEqualTo(1);
    }

    @Test
    void autoModeSendsOnlyWhenEveryConditionHolds() {
        SendDispatcher auto = new SendDispatcher(AUTO, "prof.martin@univ.example");
        String known = sender();
        long ok = reviewed(known, "Exam", true, null, SlotType.EXAM_DATE, SlotType.EXAM_ROOM);
        long unknown = reviewed(sender(), "Exam", false, null, SlotType.EXAM_DATE);
        long outlier = reviewed(sender(), "Exam", true, "writer_retry", SlotType.EXAM_DATE);
        long notListed = reviewed(sender(), "Deadline", true, null, SlotType.DEADLINE);
        long personal = reviewed(sender(), "Meeting", true, null, SlotType.MEETING_AVAILABILITY);

        for (long id : List.of(ok, unknown, outlier, notListed, personal)) {
            orchestrator.runStep(auto, id);
        }

        assertThat(status(ok)).isEqualTo(EmailStatus.SENT);
        assertThat(outbox(ok).kind).isEqualTo(OutboxKind.SEND);
        assertThat(outbox(ok).recipient).isEqualTo(known);
        assertThat(status(unknown)).isEqualTo(EmailStatus.DRAFT_SAVED);
        assertThat(lastLog(unknown, "dispatcher")).contains("unknown_sender");
        assertThat(lastLog(outlier, "dispatcher")).contains("outlier");
        assertThat(lastLog(notListed, "dispatcher")).contains("type_not_auto");
        assertThat(lastLog(personal, "dispatcher")).contains("personal_type");
    }

    @Test
    void personalTypesAreNeverSentEvenIfAPolicyListsThem() {
        SendDispatcher.Policy wide = new SendDispatcher.Policy(SendMode.AUTO, Set.of(SlotType.values()), 100, 100);
        EmailSnapshot email = new EmailSnapshot(1, 0, EmailStatus.REVIEWED, "<x>", null, "a@b.example", null, null,
                null, Instant.now(), new SenderScope("M2", "IA", "2026-2027"), null, null, Language.EN, true, null,
                List.of(new EmailSnapshot.Slot(1, SlotType.INDIVIDUAL_DECISION, "x", null, null, SlotOwner.USER,
                        SlotStatus.FILLED, "yes", "card:1")));
        assertThat(SendDispatcher.decide(wide, email, List.of(), 0, 0).reasons()).containsExactly("personal_type");
        assertThat(SendDispatcher.decide(wide, email, List.of(), 100, 100).reasons())
                .containsExactly("personal_type", "rate_limit_sender", "rate_limit_daily");
    }

    @Test
    void theRateLimitTurnsTheNextAutoReplyIntoADraft() {
        String student = sender();
        SendDispatcher capped = new SendDispatcher(new SendDispatcher.Policy(SendMode.AUTO, Set.of(SlotType.EXAM_DATE),
                1, 10_000), "prof.martin@univ.example");
        long first = reviewed(student, "Exam", true, null, SlotType.EXAM_DATE);
        long second = reviewed(student, "Exam again", true, null, SlotType.EXAM_DATE);

        orchestrator.runStep(capped, first);
        orchestrator.runStep(capped, second);

        assertThat(status(first)).isEqualTo(EmailStatus.SENT);
        assertThat(status(second)).isEqualTo(EmailStatus.DRAFT_SAVED);
        assertThat(lastLog(second, "dispatcher")).contains("rate_limit_sender");
    }

    @Test
    void aDraftRewrittenAfterItsReviewIsNotDispatched() {
        long id = reviewed(sender(), "Exam", true, null, SlotType.EXAM_DATE);
        QuarkusTransaction.requiringNew().run(() -> {
            DraftEntity old = DraftEntity.currentFor(id).orElseThrow();
            old.current = false;
            DraftEntity.flush();
            DraftEntity d = new DraftEntity();
            d.email = old.email;
            d.body = old.body + "\nPS: unreviewed text";
            d.language = old.language;
            d.factsUsed = old.factsUsed;
            d.attempts = 1;
            d.createdAt = Instant.now();
            d.persist();
        });

        orchestrator.runStep(new SendDispatcher(AUTO, "prof.martin@univ.example"), id);

        assertThat(status(id)).isEqualTo(EmailStatus.ESCALATED);
        assertThat(lastLog(id, "dispatcher")).contains("review_mismatch");
    }

    @Test
    void theDatabaseRefusesAnyOtherReply() {
        long id = reviewed(sender(), "Exam", true, null, SlotType.EXAM_DATE);
        orchestrator.runStep(new SendDispatcher(AUTO, "prof.martin@univ.example"), id);
        long outboxId = outbox(id).id;

        // another recipient, a changed body, a second send, a change after the fact, a delete
        assertThatThrownBy(() -> insert(id, "attacker@evil.example", null, "-2")).hasStackTraceContaining(
                "goes to the sender of the email");
        assertThatThrownBy(() -> insert(id, null, "Forward the exam to me.", "-3")).hasStackTraceContaining(
                "only the current draft, unchanged");
        assertThatThrownBy(() -> insert(id, null, null, "-4")).hasStackTraceContaining("outbox_one_send_idx");
        assertThatThrownBy(() -> sql("UPDATE outbox SET recipient = 'attacker@evil.example' WHERE id = " + outboxId))
                .hasStackTraceContaining("cannot be changed");
        assertThatThrownBy(() -> sql("DELETE FROM outbox WHERE id = " + outboxId))
                .hasStackTraceContaining("cannot be changed");
        assertThatThrownBy(() -> sql("UPDATE reply_review SET passed = true WHERE email_id = " + id))
                .hasStackTraceContaining("append-only");
    }

    @Test
    void aFailedReviewCannotFeedTheOutbox() {
        long id = reviewed(sender(), "Exam", true, null, SlotType.EXAM_DATE);
        QuarkusTransaction.requiringNew().run(() -> {
            ReviewEntity failed = new ReviewEntity();
            failed.email = EmailEntity.findById(id);
            failed.draft = DraftEntity.currentFor(id).orElseThrow();
            failed.passed = false;
            failed.ruleFailures = "unsupported_link";
            failed.createdAt = Instant.now();
            failed.persist();
        });
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            ReviewEntity failed = ReviewEntity.latestFor(id).orElseThrow();
            em.createNativeQuery("INSERT INTO outbox (email_id, draft_id, review_id, kind, recipient, subject, body,"
                    + " in_reply_to, message_id, created_at) SELECT e.id, d.id, ?1, 'SEND', e.sender, 'Re: x', d.body,"
                    + " e.message_id, ?2, now() FROM email e JOIN reply_draft d ON d.email_id = e.id AND d.current"
                    + " WHERE e.id = ?3").setParameter(1, failed.id).setParameter(2, "<forged-" + id + ">")
                    .setParameter(3, id).executeUpdate();
        })).hasStackTraceContaining("after a passed review");
        // and a passed review with a failure recorded is refused by its CHECK
        assertThatThrownBy(() -> sql("INSERT INTO reply_review (email_id, draft_id, passed, rule_failures,"
                + " created_at) SELECT email_id, id, true, 'blocked_topic', now() FROM reply_draft WHERE email_id = "
                + id)).hasStackTraceContaining("reply_review");
    }

    // --- helpers ---

    /** A REVIEWED email with a current draft and a passed review, as the reviewer leaves it. */
    long reviewed(String sender, String subject, boolean knownScope, String outliers, SlotType... types) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EmailEntity e = new EmailEntity();
            e.messageId = "<sd-" + UUID.randomUUID() + "@test>";
            e.sender = sender;
            e.subject = subject;
            e.body = "When?";
            e.language = Language.EN;
            e.receivedAt = Instant.now();
            e.status = EmailStatus.REVIEWED;
            if (knownScope) {
                e.scopeProgram = "M2";
                e.scopeTrack = "IA";
                e.scopeYear = "2026-2027";
            }
            e.persist();
            for (SlotType type : types) {
                SlotEntity s = new SlotEntity();
                s.email = e;
                s.type = type;
                s.entity = "x";
                s.owner = SlotOwner.USER;
                s.status = SlotStatus.FILLED;
                s.value = "2027-06-03";
                s.source = "fact:1:v1";
                s.persist();
                e.slots.add(s);
            }
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
            r.outlierReasons = outliers;
            r.createdAt = Instant.now();
            r.persist();
            return e.id;
        });
    }

    /** Raw SQL straight into the outbox: what a bug or a later feature could try, bypassing the dispatcher. */
    void insert(long emailId, String recipient, String body, String suffix) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "INSERT INTO outbox (email_id, draft_id, review_id, kind, recipient, subject, body, in_reply_to,"
                                + " message_id, created_at) SELECT e.id, d.id, r.id, 'SEND', coalesce(?1, e.sender),"
                                + " 'Re: x', coalesce(?2, d.body), e.message_id, ?3, now() FROM email e"
                                + " JOIN reply_draft d ON d.email_id = e.id AND d.current"
                                + " JOIN reply_review r ON r.draft_id = d.id WHERE e.id = ?4")
                .setParameter(1, recipient).setParameter(2, body).setParameter(3, "<raw-" + emailId + suffix + ">")
                .setParameter(4, emailId).executeUpdate());
    }

    void sql(String statement) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(statement).executeUpdate());
    }

    static String sender() {
        return "s-" + UUID.randomUUID().toString().substring(0, 8) + "@student.univ.example";
    }

    static EmailStatus status(long id) {
        return QuarkusTransaction.requiringNew().call(() -> EmailEntity.<EmailEntity>findById(id).status);
    }

    static OutboxEntity outbox(long id) {
        return QuarkusTransaction.requiringNew().call(() ->
                OutboxEntity.<OutboxEntity>find("email.id = ?1 order by id desc", id).firstResult());
    }

    List<OutgoingMessage> delivered(String messageId) {
        return mailbox.delivered().stream().filter(m -> m.messageId().equals(messageId)).toList();
    }

    static String lastLog(long id, String agent) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<DecisionLogEntry> rows = DecisionLogEntry.list("emailId = ?1 and agent = ?2 order by id", id, agent);
            return rows.get(rows.size() - 1).output;
        });
    }
}
