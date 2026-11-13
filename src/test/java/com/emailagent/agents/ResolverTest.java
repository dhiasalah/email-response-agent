package com.emailagent.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.emailagent.model.EmailStatus;
import com.emailagent.model.SenderScope;
import com.emailagent.model.SlotKey;
import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotStatus;
import com.emailagent.model.SlotType;
import com.emailagent.orchestrator.Orchestrator;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.FactEntity;
import com.emailagent.store.RecentAnswerEntity;
import com.emailagent.store.SlotEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reuse rules (design doc §8.1, §8.4): right scope, still valid, shareable, or nothing. */
@QuarkusTest
class ResolverTest {

    static final SenderScope M2_IA = new SenderScope("M2", "IA", "2026-2027");
    static final SenderScope M1_IA = new SenderScope("M1", "IA", "2026-2027");

    @Inject Orchestrator orchestrator;
    @Inject ResolverAgent resolver;

    @Test
    void validFactInTheSameScopeFillsTheSlot() {
        String entity = unique("s2 exams");
        fact(SlotType.GRADES_PUBLICATION_DATE, entity, M2_IA, "12 May", true, null, Duration.ZERO);
        long id = email(M2_IA, slot(SlotType.GRADES_PUBLICATION_DATE, entity, SlotOwner.USER));

        orchestrator.runStep(resolver);

        assertThat(status(id)).isEqualTo(EmailStatus.READY);
        assertThat(slots(id)).singleElement().satisfies(s -> {
            assertThat(s.status).isEqualTo(SlotStatus.FILLED);
            assertThat(s.value).isEqualTo("12 May");
            assertThat(s.source).startsWith("fact:").endsWith(":v1");
        });
    }

    @Test
    void anotherScopeOrAnUnknownScopeGetsNothing() {
        String entity = unique("s2 exams");
        fact(SlotType.GRADES_PUBLICATION_DATE, entity, M2_IA, "12 May", true, null, Duration.ZERO);
        long m1 = email(M1_IA, slot(SlotType.GRADES_PUBLICATION_DATE, entity, SlotOwner.USER));
        long unknown = email(SenderScope.UNKNOWN, slot(SlotType.GRADES_PUBLICATION_DATE, entity, SlotOwner.USER));

        orchestrator.runStep(resolver);

        assertThat(status(m1)).isEqualTo(EmailStatus.AWAITING_INPUT); // a card, not M2's answer
        assertThat(status(unknown)).isEqualTo(EmailStatus.AWAITING_INPUT);
        assertThat(slots(m1)).singleElement().extracting(s -> s.status).isEqualTo(SlotStatus.UNRESOLVED);
    }

    @Test
    void expiredPersonalOrStaleFactsAreNotReusedDirectly() {
        String expired = unique("deadline");
        fact(SlotType.DEADLINE, expired, M2_IA, "1 Oct", true, Instant.now().minus(Duration.ofDays(1)), Duration.ZERO);
        String personal = unique("procedure");
        fact(SlotType.PROCEDURE, personal, M2_IA, "for you only", false, null, Duration.ZERO);
        String stale = unique("exam date");
        fact(SlotType.EXAM_DATE, stale, M2_IA, "3 June", true, null, Duration.ofDays(200));

        long a = email(M2_IA, slot(SlotType.DEADLINE, expired, SlotOwner.USER));
        long b = email(M2_IA, slot(SlotType.PROCEDURE, personal, SlotOwner.USER));
        long c = email(M2_IA, slot(SlotType.EXAM_DATE, stale, SlotOwner.USER));

        orchestrator.runStep(resolver);

        assertThat(slots(a)).singleElement().extracting(s -> s.status).isEqualTo(SlotStatus.UNRESOLVED);
        assertThat(slots(b)).singleElement().extracting(s -> s.status).isEqualTo(SlotStatus.UNRESOLVED);
        assertThat(slots(c)).singleElement().satisfies(s -> { // old fact: reuse only after the owner looks
            assertThat(s.status).isEqualTo(SlotStatus.PROPOSED);
            assertThat(s.value).isEqualTo("3 June");
        });
        assertThat(status(c)).isEqualTo(EmailStatus.AWAITING_INPUT);
    }

    @Test
    void knowledgeBaseFillsAndCalendarOnlyProposes() {
        // kb1 has no scope (regulations apply to everyone), so it works even for an unknown sender
        long appeal = email(SenderScope.UNKNOWN, slot(SlotType.PROCEDURE, "Grade appeals", SlotOwner.USER));
        long grades = email(M2_IA, slot(SlotType.GRADES_PUBLICATION_DATE, "s2 exams", SlotOwner.USER));
        long gradesM1 = email(M1_IA, slot(SlotType.GRADES_PUBLICATION_DATE, "s2 exams", SlotOwner.USER));

        orchestrator.runStep(resolver);

        assertThat(status(appeal)).isEqualTo(EmailStatus.READY);
        assertThat(slots(appeal)).singleElement().extracting(s -> s.source).asString().startsWith("kb:kb1");
        assertThat(slots(grades)).singleElement().satisfies(s -> {
            assertThat(s.status).isEqualTo(SlotStatus.PROPOSED);
            assertThat(s.value).isEqualTo("18 June 2027");
            assertThat(s.source).startsWith("calendar:ev1");
        });
        assertThat(slots(gradesM1)).singleElement().extracting(s -> s.status).isEqualTo(SlotStatus.UNRESOLVED);
    }

    @Test
    void recentAnswersAreProposedAndConflictsSaySo() {
        String once = unique("room");
        recent(SlotType.EXAM_ROOM, once, "A101", Duration.ofDays(1));
        String twice = unique("room");
        recent(SlotType.EXAM_ROOM, twice, "A102", Duration.ofDays(2));
        recent(SlotType.EXAM_ROOM, twice, "a102", Duration.ofDays(1));
        String conflict = unique("room");
        recent(SlotType.EXAM_ROOM, conflict, "A103", Duration.ofDays(2));
        recent(SlotType.EXAM_ROOM, conflict, "B201", Duration.ofDays(1));
        String old = unique("room");
        recent(SlotType.EXAM_ROOM, old, "C301", Duration.ofDays(10)); // outside the 7-day window

        long a = email(M2_IA, slot(SlotType.EXAM_ROOM, once, SlotOwner.USER));
        long b = email(M2_IA, slot(SlotType.EXAM_ROOM, twice, SlotOwner.USER));
        long c = email(M2_IA, slot(SlotType.EXAM_ROOM, conflict, SlotOwner.USER));
        long d = email(M2_IA, slot(SlotType.EXAM_ROOM, old, SlotOwner.USER));

        orchestrator.runStep(resolver);

        assertThat(slots(a)).singleElement().extracting(s -> s.status).isEqualTo(SlotStatus.PROPOSED);
        // repeated, but no slot type is trusted at launch: still only proposed
        assertThat(slots(b)).singleElement().extracting(s -> s.status).isEqualTo(SlotStatus.PROPOSED);
        assertThat(slots(c)).singleElement().satisfies(s -> {
            assertThat(s.value).isEqualTo("B201"); // newest
            assertThat(s.source).contains("conflict: 2 different answers");
        });
        assertThat(slots(d)).singleElement().extracting(s -> s.status).isEqualTo(SlotStatus.UNRESOLVED);
    }

    @Test
    void joinRule() {
        String entity = unique("s2 exams");
        fact(SlotType.EXAM_DATE, entity, M2_IA, "3 June", true, null, Duration.ZERO);
        long partly = email(M2_IA, slot(SlotType.EXAM_DATE, entity, SlotOwner.USER),
                slot(SlotType.EXAM_ROOM, unique("x"), SlotOwner.USER));
        long senderOwned = email(M2_IA, slot(SlotType.EXAM_DATE, entity, SlotOwner.USER),
                slot(SlotType.DEADLINE, unique("which course"), SlotOwner.SENDER));
        long meeting = email(M2_IA, slot(SlotType.MEETING_AVAILABILITY, "internship", SlotOwner.USER));

        orchestrator.runStep(resolver);

        assertThat(status(partly)).isEqualTo(EmailStatus.AWAITING_INPUT); // one filled is not enough
        assertThat(status(senderOwned)).isEqualTo(EmailStatus.AWAITING_SENDER);
        assertThat(status(meeting)).isEqualTo(EmailStatus.AWAITING_INPUT); // never from memory
    }

    // --- specialized agents (brief §5.2): each agent reads its own context only ---

    @Test
    void anAgentNeverReadsAnotherAgentsMemory() {
        String grades = unique("s2 exams");
        fact(SlotType.GRADES_PUBLICATION_DATE, grades, M2_IA, "12 May", true, null, Duration.ZERO); // student's
        String room = unique("room");
        recent(SlotType.EXAM_ROOM, room, "A101", Duration.ofDays(1)); // learned from a student email

        long student = email(M2_IA, slot(SlotType.GRADES_PUBLICATION_DATE, grades, SlotOwner.USER));
        long colleague = emailFor("colleague", M2_IA, slot(SlotType.GRADES_PUBLICATION_DATE, grades, SlotOwner.USER),
                slot(SlotType.EXAM_ROOM, room, SlotOwner.USER));

        orchestrator.runStep(resolver);

        assertThat(status(student)).isEqualTo(EmailStatus.READY);
        assertThat(status(colleague)).isEqualTo(EmailStatus.AWAITING_INPUT); // same scope, other agent: a card
        assertThat(slots(colleague)).allSatisfy(s -> {
            assertThat(s.status).isEqualTo(SlotStatus.UNRESOLVED);
            assertThat(s.value).isNull();
        });
    }

    @Test
    void eachAgentReadsItsOwnKnowledgeBase() {
        // "grade submission" is in the colleague's knowledge base, not the student's; the grade appeal procedure
        // is in the student's only
        long colleague = emailFor("colleague", SenderScope.UNKNOWN,
                slot(SlotType.DEADLINE, "grade submission", SlotOwner.USER));
        long student = email(SenderScope.UNKNOWN, slot(SlotType.DEADLINE, "grade submission", SlotOwner.USER));
        long colleagueAppeal = emailFor("colleague", SenderScope.UNKNOWN,
                slot(SlotType.PROCEDURE, "grade appeal", SlotOwner.USER));

        orchestrator.runStep(resolver);

        assertThat(status(colleague)).isEqualTo(EmailStatus.READY);
        assertThat(slots(colleague)).singleElement().extracting(s -> s.source).asString().startsWith("kb:col1");
        assertThat(slots(student)).singleElement().extracting(s -> s.status).isEqualTo(SlotStatus.UNRESOLVED);
        assertThat(slots(colleagueAppeal)).singleElement().extracting(s -> s.status).isEqualTo(SlotStatus.UNRESOLVED);
    }

    @Test
    void anUnroutedEmailIsEscalatedRatherThanResolvedFromSomeContext() {
        long id = emailFor(null, M2_IA, slot(SlotType.GRADES_PUBLICATION_DATE, "s2 exams", SlotOwner.USER));

        orchestrator.runStep(resolver);

        assertThat(status(id)).isEqualTo(EmailStatus.ESCALATED);
    }

    // --- attacks ---

    @Test
    void databaseRefusesAShareablePersonalFact() {
        assertThatThrownBy(() -> fact(SlotType.INDIVIDUAL_RESULT, "machine learning exam", M2_IA, "14/20", true,
                null, Duration.ZERO))
                .hasStackTraceContaining("fact_check");
    }

    @Test
    void anEntityChosenByTheAttackerIsOnlyALookupKey() {
        // triage's entity comes from the model, which read the email: treat it as data in a parameterized query
        long id = email(M2_IA, slot(SlotType.EXAM_DATE, "x' OR '1'='1", SlotOwner.USER));

        orchestrator.runStep(resolver);

        assertThat(status(id)).isEqualTo(EmailStatus.AWAITING_INPUT);
        assertThat(slots(id)).singleElement().extracting(s -> s.status).isEqualTo(SlotStatus.UNRESOLVED);
    }

    // --- helpers ---

    record SlotSpec(SlotType type, String entity, SlotOwner owner) {}

    static SlotSpec slot(SlotType type, String entity, SlotOwner owner) {
        return new SlotSpec(type, entity, owner);
    }

    static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8).replaceAll("[0-9]", "q");
    }

    static long email(SenderScope scope, SlotSpec... slots) {
        return emailFor("student", scope, slots);
    }

    static long emailFor(String agentId, SenderScope scope, SlotSpec... slots) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EmailEntity e = new EmailEntity();
            e.agentId = agentId;
            e.messageId = "<res-" + UUID.randomUUID() + "@test>";
            e.sender = "student@univ.example";
            e.receivedAt = Instant.now();
            e.status = EmailStatus.RESOLVING;
            e.scopeProgram = scope.program();
            e.scopeTrack = scope.track();
            e.scopeYear = scope.year();
            e.persist();
            for (SlotSpec spec : slots) {
                SlotEntity s = new SlotEntity();
                s.email = e;
                s.type = spec.type();
                s.entity = spec.entity();
                s.owner = spec.owner();
                s.persist();
            }
            return e.id;
        });
    }

    static void fact(SlotType type, String entity, SenderScope scope, String value, boolean shareable,
            Instant validUntil, Duration age) {
        QuarkusTransaction.requiringNew().run(() -> {
            FactEntity f = new FactEntity();
            f.agentId = "student";
            f.slotType = type;
            f.entityKey = new SlotKey(type, entity, null).entity();
            f.scopeProgram = scope.program();
            f.scopeTrack = scope.track();
            f.scopeYear = scope.year();
            f.value = value;
            f.shareable = shareable;
            f.validUntil = validUntil;
            f.source = "card:test answered by owner";
            f.createdAt = Instant.now().minus(age);
            f.persist();
        });
    }

    static void recent(SlotType type, String entity, String value, Duration age) {
        QuarkusTransaction.requiringNew().run(() -> {
            RecentAnswerEntity r = new RecentAnswerEntity();
            r.agentId = "student";
            r.slotType = type;
            r.entityKey = new SlotKey(type, entity, null).entity();
            r.scopeProgram = M2_IA.program();
            r.scopeTrack = M2_IA.track();
            r.scopeYear = M2_IA.year();
            r.value = value;
            r.answeredAt = Instant.now().minus(age);
            r.persist();
        });
    }

    static EmailStatus status(long id) {
        return QuarkusTransaction.requiringNew().call(() -> EmailEntity.<EmailEntity>findById(id).status);
    }

    static List<SlotEntity> slots(long id) {
        return QuarkusTransaction.requiringNew().call(() -> SlotEntity.<SlotEntity>list("email.id = ?1 order by id", id));
    }
}
