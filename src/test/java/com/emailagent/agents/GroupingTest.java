package com.emailagent.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.emailagent.config.AppConfig;
import com.emailagent.llm.FakeEmbedder;
import com.emailagent.llm.FakeLlmClient;
import com.emailagent.llm.LlmOutputParser;
import com.emailagent.llm.LlmRequest;
import com.emailagent.model.ClusterStatus;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.FieldType;
import com.emailagent.model.GroupedBy;
import com.emailagent.model.SenderScope;
import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotStatus;
import com.emailagent.model.SlotType;
import com.emailagent.orchestrator.GroupingRun;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.InputRequestEntity;
import com.emailagent.store.SlotEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Grouping by required input (design doc §7.2): exact keys join for free, near keys need the verifier, and the
 * code gates (type, scope, qualifier, numbers, personal) hold even when the verifier says yes to everything.
 * Each test uses its own scope, so other tests' waiting questions can never be candidates.
 */
@QuarkusTest
class GroupingTest {

    static final String YES = "{\"sameInput\": true}";
    static final String NO = "{\"sameInput\": false}";

    @Inject GroupingRun grouping;
    @Inject AppConfig config;
    @Inject LlmOutputParser parser;
    @Inject EntityManager em;

    FakeLlmClient model;
    FakeEmbedder embedder;
    GroupingAgent agent;
    SenderScope scope;

    @BeforeEach
    void setUp() {
        model = new FakeLlmClient(parser);
        embedder = new FakeEmbedder();
        agent = new GroupingAgent(model, embedder, config, "scripted");
        scope = new SenderScope("M2", "IA", "y-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    void sameKeyJoinsOneCardWithoutAskingTheModel() {
        long a = email("a@s.example", scope, q(SlotType.GRADES_PUBLICATION_DATE, "s2 exams", "Quand les notes du S2 ?"));
        long b = email("b@s.example", scope, q(SlotType.GRADES_PUBLICATION_DATE, "S2 Exams", "When are S2 grades out?"));
        long c = email("c@s.example", scope, q(SlotType.GRADES_PUBLICATION_DATE, "s2 exam", "Résultats du S2 ?"));

        grouping.run(agent);

        InputRequestEntity card = cardOf(a);
        assertThat(cardOf(b).id).isEqualTo(card.id);
        assertThat(cardOf(c).id).isEqualTo(card.id);
        assertThat(card.memberCount).isEqualTo(3);
        assertThat(card.personal).isFalse();
        assertThat(card.fieldType).isEqualTo(FieldType.DATE);
        assertThat(card.question).isEqualTo("Grade publication date: s2 exam — M2 IA " + scope.year());
        assertThat(card.centroid).hasSize(64);
        assertThat(card.priority).isPositive();
        assertThat(slot(a).groupedBy).isEqualTo(GroupedBy.NEW);
        assertThat(slot(b).groupedBy).isEqualTo(GroupedBy.SAME_KEY);
        assertThat(myRequests()).isEmpty(); // FR and EN are only 0.67 apart for the embedder: the key did the work
    }

    @Test
    void differentScopeQualifierOrPersonalNeverShareACard() {
        SenderScope m1 = new SenderScope("M1", "IA", scope.year());
        long m2 = email("a@s.example", scope, q(SlotType.EXAM_ROOM, "s2 exams", "Which room?"));
        long other = email("b@s.example", m1, q(SlotType.EXAM_ROOM, "s2 exams", "Which room?"));
        long retake = email("c@s.example", scope, slot(SlotType.EXAM_ROOM, "s2 exams", "retake", "Which room, retake?",
                SlotStatus.UNRESOLVED, null, null));
        long ext1 = email("d@s.example", scope, q(SlotType.INDIVIDUAL_DECISION, "project extension", "Extension?"));
        long ext2 = email("e@s.example", scope, q(SlotType.INDIVIDUAL_DECISION, "project extension", "Extension?"));
        long unknown1 = email("f@s.example", SenderScope.UNKNOWN, q(SlotType.EXAM_DATE, "s2 exams " + scope.year(), "When?"));
        long unknown2 = email("g@s.example", SenderScope.UNKNOWN, q(SlotType.EXAM_DATE, "s2 exams " + scope.year(), "When?"));

        grouping.run(agent);

        assertThat(List.of(cardOf(m2).id, cardOf(other).id, cardOf(retake).id, cardOf(ext1).id, cardOf(ext2).id,
                cardOf(unknown1).id, cardOf(unknown2).id)).doesNotHaveDuplicates();
        assertThat(cardOf(ext1).personal).isTrue();
        assertThat(cardOf(ext1).question).endsWith("this sender only");
        assertThat(cardOf(ext1).centroid).isNull(); // never matched, so never embedded
        assertThat(slot(unknown1).groupedBy).isEqualTo(GroupedBy.PERSONAL);
        assertThat(myRequests()).isEmpty();
    }

    @Test
    void nearKeyIsJoinedOnlyIfTheVerifierAgrees() {
        String tag = scope.year();
        embedder.pin("When are semester 2 grades out? " + tag, 1, 0, 0)
                .pin("S2 grade publication? " + tag, 0.95f, 0.31f, 0)
                .pin("Are the semester 2 results ready? " + tag, 0.93f, 0.36f, 0);
        model.willAnswer(YES).willAnswer(NO);
        long first = email("a@s.example", scope, q(SlotType.GRADES_PUBLICATION_DATE, "semester 2 exams",
                "When are semester 2 grades out? " + tag));
        long agreed = email("b@s.example", scope, q(SlotType.GRADES_PUBLICATION_DATE, "s2 exams",
                "S2 grade publication? " + tag));
        long refused = email("c@s.example", scope, q(SlotType.GRADES_PUBLICATION_DATE, "semester 2 results",
                "Are the semester 2 results ready? " + tag));

        grouping.run(agent);

        assertThat(cardOf(agreed).id).isEqualTo(cardOf(first).id);
        assertThat(slot(agreed).groupedBy).isEqualTo(GroupedBy.VERIFIED);
        assertThat(cardOf(refused).id).isNotEqualTo(cardOf(first).id);
        assertThat(myRequests()).hasSize(2).allSatisfy(r -> {
            assertThat(r.agent()).isEqualTo("grouping");
            assertThat(r.untrustedInput()).contains("Question A (already on the card)");
        });
        assertThat(lastLog(refused)).contains("\"note\":\"verifier_rejected\"").contains("\"similarity\":");
    }

    // --- attacks ---

    @Test
    void anObedientVerifierCannotCrossTheCodeGates() {
        // the attacker's email convinced the verifier to say "same" to everything, and the vectors are identical
        String tag = scope.year();
        for (String text : List.of("s1 grades? " + tag, "s2 grades? " + tag, "exam date? " + tag, "exam room? " + tag,
                "retake room? " + tag)) {
            embedder.pin(text, 1, 0, 0);
        }
        for (int i = 0; i < 10; i++) {
            model.willAnswer(YES);
        }
        long s1 = email("a@s.example", scope, q(SlotType.GRADES_PUBLICATION_DATE, "s1 exams", "s1 grades? " + tag));
        long s2 = email("b@s.example", scope, q(SlotType.GRADES_PUBLICATION_DATE, "s2 exams", "s2 grades? " + tag));
        long date = email("c@s.example", scope, q(SlotType.EXAM_DATE, "ml exam", "exam date? " + tag));
        long room = email("d@s.example", scope, q(SlotType.EXAM_ROOM, "ml exam", "exam room? " + tag));
        long retake = email("e@s.example", scope, slot(SlotType.EXAM_ROOM, "ml exam", "retake", "retake room? " + tag,
                SlotStatus.UNRESOLVED, null, null));

        grouping.run(agent);

        assertThat(List.of(cardOf(s1).id, cardOf(s2).id, cardOf(date).id, cardOf(room).id, cardOf(retake).id))
                .doesNotHaveDuplicates();
        assertThat(myRequests()).isEmpty(); // never even asked: the gates refused first
        assertThat(lastLog(s2)).contains("number_mismatch");
    }

    @Test
    void verifierOrEmbedderDownKeepsQuestionsApart() {
        String tag = scope.year();
        embedder.pin("first? " + tag, 1, 0, 0).pin("second? " + tag, 0.99f, 0.1f, 0).failOn("third? " + tag);
        // no answer queued: the verifier fails
        long first = email("a@s.example", scope, q(SlotType.DEADLINE, "mas project", "first? " + tag));
        long second = email("b@s.example", scope, q(SlotType.DEADLINE, "multi agent project", "second? " + tag));
        long third = email("c@s.example", scope, q(SlotType.DEADLINE, "multi-agent systems project", "third? " + tag));

        grouping.run(agent);

        assertThat(List.of(cardOf(first).id, cardOf(second).id, cardOf(third).id)).doesNotHaveDuplicates();
        assertThat(lastLog(second)).contains("verifier_failed");
        assertThat(lastLog(third)).contains("embedding_failed");
        assertThat(cardOf(third).centroid).isNull(); // still findable later by exact key
    }

    @Test
    void lateQuestionJoinsTheOpenCardAndRunsAreIdempotent() {
        long first = email("a@s.example", scope, q(SlotType.EXAM_DATE, "s2 exams", "When is the S2 exam?"));
        grouping.run(agent);
        InputRequestEntity before = cardOf(first);

        long late = email("b@s.example", scope, q(SlotType.EXAM_DATE, "s2 exams", "Date of S2 exam?"));
        grouping.run(agent);
        grouping.run(agent);

        InputRequestEntity after = cardOf(late);
        assertThat(after.id).isEqualTo(before.id);
        assertThat(after.memberCount).isEqualTo(2);
        assertThat(after.centroid).isNotEqualTo(before.centroid);
        assertThat(QuarkusTransaction.requiringNew().call(() ->
                DecisionLogEntry.count("emailId = ?1 and agent = 'grouping'", late))).isEqualTo(1);
    }

    @Test
    void proposalsAreSharedOnlyWhenTheyAgree() {
        long a = email("a@s.example", scope, slot(SlotType.EXAM_DATE, "ml exam", null, "When?",
                SlotStatus.PROPOSED, "18 June 2027", "calendar:ev9"));
        long b = email("b@s.example", scope, q(SlotType.EXAM_DATE, "ml exam", "When is it?"));
        long c = email("c@s.example", scope, slot(SlotType.EXAM_DATE, "ml exam", null, "Exam?",
                SlotStatus.PROPOSED, "2 July 2027", "recent:5"));

        grouping.run(agent);

        InputRequestEntity card = cardOf(a);
        assertThat(cardOf(b).id).isEqualTo(card.id);
        assertThat(card.proposedValue).isEqualTo("18 June 2027");
        assertThat(card.proposalSource).isEqualTo("calendar:ev9");
        assertThat(cardOf(c).id).isNotEqualTo(card.id); // a conflicting proposal gets its own card
    }

    @Test
    void emptyCardClosesOnlyAfterExpiry() {
        long id = email("a@s.example", scope, q(SlotType.PROCEDURE, "re-mark", "How to ask for a re-mark?"));
        grouping.run(agent);
        long cardId = cardOf(id).id;
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE email SET status = 'ESCALATED' WHERE id = ?1").setParameter(1, id)
                    .executeUpdate();
            em.createNativeQuery("UPDATE email SET status = 'CLOSED' WHERE id = ?1").setParameter(1, id)
                    .executeUpdate();
        });

        grouping.run(agent);
        assertThat(card(cardId).status).isEqualTo(ClusterStatus.OPEN); // empty is not closed

        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE input_request SET last_activity = now() - interval '8 days' WHERE id = ?1")
                .setParameter(1, cardId).executeUpdate());
        grouping.run(agent);
        assertThat(card(cardId).status).isEqualTo(ClusterStatus.CLOSED);
    }

    @Test
    void moreSendersAndATrustedRoleRankHigher() {
        long crowd1 = email("a@s.example", scope, q(SlotType.DEADLINE, "report", "Due date?"));
        email("b@s.example", scope, q(SlotType.DEADLINE, "report", "Due date?"));
        email("c@s.example", scope, q(SlotType.DEADLINE, "report", "Due date?"));
        long alone = email("d@s.example", scope, q(SlotType.DEADLINE, "slides", "Due date?"));
        // "URGENT" from one unknown sender, 3 times: one distinct sender, wording capped
        long loud = email("e@s.example", scope, q(SlotType.DEADLINE, "poster", "URGENT due date? asap"));
        email("e@s.example", scope, q(SlotType.DEADLINE, "poster", "URGENT due date? asap"));
        long head = email("head.ia@univ.example", scope, q(SlotType.DEADLINE, "budget", "Due date?"));

        grouping.run(agent);

        assertThat(cardOf(crowd1).priority).isGreaterThan(cardOf(alone).priority);
        assertThat(cardOf(head).priority).isGreaterThan(cardOf(alone).priority);
        assertThat(cardOf(loud).priority).isLessThan(0.5);
    }

    @Test
    void databaseRefusesWrongScopeTypeOrASecondPersonalMember() {
        long a = email("a@s.example", scope, q(SlotType.EXAM_DATE, "s2 exams", "When?"));
        long personal = email("b@s.example", scope, q(SlotType.INDIVIDUAL_DECISION, "extension", "Extension?"));
        long otherScope = email("c@s.example", new SenderScope("M1", "IA", scope.year()),
                q(SlotType.EXAM_DATE, "s2 exams", "When?"));
        long otherPersonal = email("d@s.example", scope, q(SlotType.INDIVIDUAL_DECISION, "extension", "Extension?"));
        long room = email("e@s.example", scope, q(SlotType.EXAM_ROOM, "s2 exams", "Where?"));
        grouping.run(agent);
        long shared = cardOf(a).id;
        long own = cardOf(personal).id;

        // a bug, a raw query or a future panel endpoint tries to put slots where they don't belong
        assertThatThrownBy(() -> setRequest(slot(otherScope).id, shared)).hasStackTraceContaining("another scope");
        assertThatThrownBy(() -> setRequest(slot(room).id, shared)).hasStackTraceContaining("another slot type");
        assertThatThrownBy(() -> setRequest(slot(otherPersonal).id, own)).hasStackTraceContaining("already has a member");
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "INSERT INTO input_request (slot_type, entity_key, personal, question, field_type, created_at,"
                        + " last_activity, scope_program, scope_track, scope_year) VALUES ('INDIVIDUAL_DECISION',"
                        + " 'extension', false, 'q', 'YES_NO', now(), now(), 'M2', 'IA', 'x')").executeUpdate()))
                .hasStackTraceContaining("input_request_shared_chk");
    }

    // --- helpers ---

    record SlotSpec(SlotType type, String entity, String qualifier, String question, SlotStatus status, String value,
            String source) {}

    static SlotSpec q(SlotType type, String entity, String question) {
        return new SlotSpec(type, entity, null, question, SlotStatus.UNRESOLVED, null, null);
    }

    static SlotSpec slot(SlotType type, String entity, String qualifier, String question, SlotStatus status,
            String value, String source) {
        return new SlotSpec(type, entity, qualifier, question, status, value, source);
    }

    /** Every verifier call of this test's run (other tests' leftovers never get near a pinned vector). */
    List<LlmRequest> myRequests() {
        return model.requests();
    }

    static long email(String sender, SenderScope scope, SlotSpec... slots) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EmailEntity e = new EmailEntity();
            e.messageId = "<grp-" + UUID.randomUUID() + "@test>";
            e.sender = sender;
            e.subject = "Question";
            e.receivedAt = Instant.now().minus(Duration.ofHours(1));
            e.status = EmailStatus.AWAITING_INPUT;
            e.scopeProgram = scope.program();
            e.scopeTrack = scope.track();
            e.scopeYear = scope.year();
            e.persist();
            for (SlotSpec spec : slots) {
                SlotEntity s = new SlotEntity();
                s.email = e;
                s.type = spec.type();
                s.entity = spec.entity();
                s.qualifier = spec.qualifier();
                s.question = spec.question();
                s.owner = SlotOwner.USER;
                s.status = spec.status();
                s.value = spec.value();
                s.source = spec.source();
                s.persist();
            }
            return e.id;
        });
    }

    static SlotEntity slot(long emailId) {
        return QuarkusTransaction.requiringNew().call(() ->
                SlotEntity.<SlotEntity>find("email.id = ?1", emailId).singleResult());
    }

    static InputRequestEntity cardOf(long emailId) {
        Long requestId = slot(emailId).requestId;
        assertThat(requestId).as("email %d has a card", emailId).isNotNull();
        return card(requestId);
    }

    static InputRequestEntity card(long id) {
        return QuarkusTransaction.requiringNew().call(() -> InputRequestEntity.<InputRequestEntity>findById(id));
    }

    static String lastLog(long emailId) {
        return QuarkusTransaction.requiringNew().call(() -> DecisionLogEntry.<DecisionLogEntry>find(
                "emailId = ?1 and agent = 'grouping' order by id desc", emailId).firstResult().output);
    }

    void setRequest(long slotId, long requestId) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE slot SET request_id = ?1, grouped_by = 'SAME_KEY' WHERE id = ?2")
                .setParameter(1, requestId).setParameter(2, slotId).executeUpdate());
    }
}
