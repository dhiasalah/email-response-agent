package com.emailagent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.emailagent.model.ClusterStatus;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.FieldType;
import com.emailagent.model.GroupedBy;
import com.emailagent.model.Language;
import com.emailagent.model.NoticeKind;
import com.emailagent.model.OutboxKind;
import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotStatus;
import com.emailagent.model.SlotType;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.FactEntity;
import com.emailagent.store.InputRequestEntity;
import com.emailagent.store.NoticeEntity;
import com.emailagent.store.OutboxEntity;
import com.emailagent.store.SlotEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Broadcast suggestions (design doc §7.7): detected by code, never sent without the owner's approval. */
@QuarkusTest
class BroadcastTest {

    @Inject BroadcastRun broadcasts;
    @Inject EntityManager em;

    @Test
    void theTriggerNeedsAGroupAShareableAnswerAListAndEnoughQuestions() {
        BroadcastRun.Candidate ok = new BroadcastRun.Candidate(false, true, false, 5, true);
        assertThat(BroadcastRun.eligible(ok, 5)).isTrue();
        assertThat(BroadcastRun.eligible(new BroadcastRun.Candidate(true, true, false, 9, true), 5)).isFalse();
        assertThat(BroadcastRun.eligible(new BroadcastRun.Candidate(false, false, false, 9, true), 5)).isFalse();
        assertThat(BroadcastRun.eligible(new BroadcastRun.Candidate(false, true, true, 9, true), 5)).isFalse();
        assertThat(BroadcastRun.eligible(new BroadcastRun.Candidate(false, true, false, 9, false), 5)).isFalse();
        assertThat(BroadcastRun.eligible(new BroadcastRun.Candidate(false, true, false, 4, true), 5)).isFalse();
    }

    @Test
    void aSuggestionWaitsForTheOwnerThenGoesToTheCohortListOnce() {
        long card = answeredCard(5);

        broadcasts.suggest();
        broadcasts.suggest(); // never suggested twice

        NoticeEntity n = broadcastFor(card);
        assertThat(n.approvedAt).isNull();
        assertThat(n.recipient).isEqualTo("m2-ia-2026@lists.univ.example"); // from the cohort directory, not an email
        assertThat(n.body).contains("3 juin 2027").contains("3 June 2027");
        assertThat(count("notice.id", n.id)).isZero(); // nothing leaves before approval

        // the database agrees: an unapproved broadcast can't enter the outbox, even by raw SQL
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "INSERT INTO outbox (notice_id, kind, recipient, subject, body, message_id, created_at)"
                        + " SELECT id, 'SEND', recipient, subject, body, '<raw-b-" + n.id + ">', now() FROM notice"
                        + " WHERE id = " + n.id).executeUpdate())).hasStackTraceContaining("only after approval");

        assertThat(broadcasts.approve(n.id)).isTrue();
        assertThat(broadcasts.approve(n.id)).isFalse();

        OutboxEntity o = QuarkusTransaction.requiringNew().call(() ->
                OutboxEntity.<OutboxEntity>find("notice.id", n.id).firstResult());
        assertThat(o.kind).isEqualTo(OutboxKind.SEND);
        assertThat(o.recipient).isEqualTo(n.recipient);
        assertThat(o.inReplyTo).isNull();
        assertThat(QuarkusTransaction.requiringNew().call(() ->
                FactEntity.<FactEntity>find("requestId", card).firstResult().broadcastAt)).isNotNull();
    }

    @Test
    void tooFewQuestionsSuggestNothing() {
        long card = answeredCard(4);

        broadcasts.suggest();

        assertThat(QuarkusTransaction.requiringNew().call(() ->
                NoticeEntity.count("request.id = ?1 and kind = ?2", card, NoticeKind.BROADCAST))).isZero();
    }

    /** An answered M2 IA card with its shareable fact and {@code questions} recent members. */
    long answeredCard(int questions) {
        String key = "exam " + UUID.randomUUID().toString().substring(0, 8);
        return QuarkusTransaction.requiringNew().call(() -> {
            InputRequestEntity c = new InputRequestEntity();
            c.agentId = "student";
            c.slotType = SlotType.EXAM_DATE;
            c.entityKey = key;
            c.scopeProgram = "M2";
            c.scopeTrack = "IA";
            c.scopeYear = "2026-2027";
            c.personal = false;
            c.question = "When is the exam?";
            c.fieldType = FieldType.DATE;
            c.status = ClusterStatus.ANSWERED;
            c.createdAt = Instant.now();
            c.lastActivity = Instant.now();
            c.persist();
            for (int i = 0; i < questions; i++) {
                EmailEntity e = new EmailEntity();
                e.agentId = "student";
                e.messageId = "<bc-" + UUID.randomUUID() + "@test>";
                e.sender = "bc" + i + "@student.univ.example";
                e.subject = "Exam";
                e.body = "When?";
                e.language = Language.EN;
                e.receivedAt = Instant.now();
                e.status = EmailStatus.READY;
                e.scopeProgram = "M2";
                e.scopeTrack = "IA";
                e.scopeYear = "2026-2027";
                e.persist();
                SlotEntity s = new SlotEntity();
                s.email = e;
                s.type = SlotType.EXAM_DATE;
                s.entity = key;
                s.owner = SlotOwner.USER;
                s.fill("2027-06-03", "card:" + c.id + " answered by owner", SlotStatus.FILLED);
                s.requestId = c.id;
                s.groupedBy = i == 0 ? GroupedBy.NEW : GroupedBy.SAME_KEY;
                s.persist();
            }
            FactEntity f = new FactEntity();
            f.agentId = "student";
            f.slotType = SlotType.EXAM_DATE;
            f.entityKey = key;
            f.scopeProgram = "M2";
            f.scopeTrack = "IA";
            f.scopeYear = "2026-2027";
            f.value = "2027-06-03";
            f.shareable = true;
            f.source = "card:" + c.id;
            f.requestId = c.id;
            f.createdAt = Instant.now();
            f.persist();
            return c.id;
        });
    }

    static NoticeEntity broadcastFor(long card) {
        return QuarkusTransaction.requiringNew().call(() ->
                NoticeEntity.<NoticeEntity>find("request.id = ?1 and kind = ?2", card, NoticeKind.BROADCAST)
                        .singleResult());
    }

    static long count(String field, Object value) {
        return QuarkusTransaction.requiringNew().call(() -> OutboxEntity.count(field, value));
    }
}
