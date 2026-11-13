package com.emailagent.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.emailagent.model.Author;
import com.emailagent.model.ClusterStatus;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.FieldType;
import com.emailagent.model.GroupedBy;
import com.emailagent.model.Language;
import com.emailagent.model.OutboxKind;
import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotStatus;
import com.emailagent.model.SlotType;
import com.emailagent.store.DecisionLogEntry;
import com.emailagent.store.DraftEntity;
import com.emailagent.store.EmailEntity;
import com.emailagent.store.FactEntity;
import com.emailagent.store.InputRequestEntity;
import com.emailagent.store.OutboxEntity;
import com.emailagent.store.ReviewEntity;
import com.emailagent.store.SlotEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The review panel (design doc §10) attacked from the outside: no session, a wrong password, a forged form without
 * its CSRF token, script in an email shown on the page; and the owner's real actions going through.
 */
@QuarkusTest
@TestProfile(OwnerProfile.class)
class PanelTest {

    @Inject OwnerIdentityProvider identities;

    @BeforeEach
    @AfterEach
    void clearLockout() {
        identities.useClock(Clock.systemUTC());
        identities.resetFailures();
    }

    @Test
    void withoutASessionEverythingLeadsToTheLoginPage() {
        Response r = given().redirects().follow(false).get("/panel");
        assertThat(r.statusCode()).isEqualTo(302);
        assertThat(r.header("Location")).endsWith("/login");
        assertThat(given().redirects().follow(false).post("/panel/escalations/1/close").statusCode()).isIn(302, 401,
                403);

        Response login = given().get("/login");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.header("Content-Security-Policy")).contains("default-src 'none'")
                .contains("frame-ancestors 'none'");
        assertThat(login.header("X-Frame-Options")).isEqualTo("DENY");
        assertThat(login.header("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void aWrongPasswordFailsAndRepeatedGuessesLockTheAccount() {
        assertThat(loginStatus("wrong")).isNull();
        assertThat(sessionCookie(OwnerProfile.PASSWORD)).isNotNull();

        Instant now = Instant.now();
        identities.useClock(Clock.fixed(now, ZoneOffset.UTC));
        for (int i = 0; i < OwnerIdentityProvider.MAX_FAILURES; i++) {
            assertThat(identities.check(OwnerProfile.USERNAME, "guess-" + i)).isFalse();
        }
        // locked: even the right password is refused until the window passes
        assertThat(identities.check(OwnerProfile.USERNAME, OwnerProfile.PASSWORD)).isFalse();
        identities.useClock(Clock.fixed(now.plus(OwnerIdentityProvider.WINDOW).plusSeconds(1), ZoneOffset.UTC));
        assertThat(identities.check(OwnerProfile.USERNAME, OwnerProfile.PASSWORD)).isTrue();
    }

    @Test
    void aFormWithoutItsCsrfTokenChangesNothing() {
        long id = escalated("Question", "Hello", "Student");
        Session s = login();

        // what a malicious page could make the owner's browser send: the session cookie, but no token
        int status = given().cookie("quarkus-credential", s.credential).redirects().follow(false)
                .contentType("application/x-www-form-urlencoded").formParam("x", "1")
                .post("/panel/escalations/" + id + "/close").statusCode();

        assertThat(status).isEqualTo(400);
        assertThat(status(id)).isEqualTo(EmailStatus.ESCALATED);

        // with the token from the page, the same action goes through
        assertThat(s.post("/panel/escalations/" + id + "/close", Map.of()).header("Location")).contains("msg=closed");
        assertThat(status(id)).isEqualTo(EmailStatus.CLOSED);
    }

    @Test
    void scriptInAnEmailIsShownAsTextNeverRun() {
        escalated("<script>alert('subject')</script>", "<svg onload=alert('body')></svg> please answer",
                "\"><img src=x onerror=alert('name')>");
        String page = login().page;

        assertThat(page).doesNotContain("<script>alert").doesNotContain("<svg onload")
                .doesNotContain("<img src=x");
        assertThat(page).contains("&lt;script&gt;alert(&#39;subject&#39;)&lt;/script&gt;")
                .contains("&lt;svg onload=alert(&#39;body&#39;)&gt;");
        // a code that isn't one of ours is not reflected at all
        String reflected = given().cookie("quarkus-credential", login().credential)
                .get("/panel?msg=<b>owned</b>").asString();
        assertThat(reflected).doesNotContain("owned");
    }

    @Test
    void theOwnerAnswersACardAndItsEmailMovesOn() {
        long[] ids = cardWithWaitingEmail();
        Session s = login();

        Response r = s.post("/panel/cards/" + ids[0] + "/answer",
                Map.of("version", String.valueOf(version(ids[0])), "value", "2027-06-03", "scope", "THESE_EMAILS"));

        assertThat(r.header("Location")).contains("msg=answered");
        assertThat(status(ids[1])).isEqualTo(EmailStatus.READY);
        // the same form again (stale version): refused, nothing changes twice
        assertThat(s.post("/panel/cards/" + ids[0] + "/answer", Map.of("version", "0", "value", "2027-06-04",
                "scope", "THESE_EMAILS")).header("Location")).contains("msg=card_changed");
    }

    @Test
    void theOwnerSendsASavedDraftOnlyOnce() {
        long id = savedDraft();
        Session s = login();

        assertThat(s.post("/panel/drafts/" + id + "/send", Map.of()).header("Location")).contains("msg=sent");
        assertThat(s.post("/panel/drafts/" + id + "/send", Map.of()).header("Location")).contains("msg=nothing");

        assertThat(status(id)).isEqualTo(EmailStatus.SENT);
        assertThat(QuarkusTransaction.requiringNew().call(() ->
                OutboxEntity.count("email.id = ?1 and kind = ?2", id, OutboxKind.SEND))).isEqualTo(1);
    }

    @Test
    void theOwnerEditsASavedDraftAndOnlyTheirVersionLeaves() {
        long id = savedDraft();
        Session s = login();
        String mine = "Hello,\n\nThe exam is on 4 June 2027, room B12.\n\nBest regards,\nProf. Martin";

        assertThat(s.post("/panel/drafts/" + id + "/edit-send", Map.of("body", mine)).header("Location"))
                .contains("msg=sent_edited");
        // a second click finds nothing to do: one reply per email
        assertThat(s.post("/panel/drafts/" + id + "/edit-send", Map.of("body", mine)).header("Location"))
                .contains("msg=nothing");

        assertThat(status(id)).isEqualTo(EmailStatus.SENT);
        QuarkusTransaction.requiringNew().run(() -> {
            List<OutboxEntity> out = OutboxEntity.list("email.id = ?1", id);
            assertThat(out).singleElement().satisfies(o -> {
                assertThat(o.kind).isEqualTo(OutboxKind.SEND);
                assertThat(o.body).isEqualTo(mine);
            });
            DraftEntity d = DraftEntity.currentFor(id).orElseThrow();
            assertThat(d.writtenBy).isEqualTo(Author.OWNER);
            assertThat(ReviewEntity.latestFor(id).orElseThrow().reviewedBy).isEqualTo(Author.OWNER);
            // the metrics must not count an edited send as an accepted draft
            assertThat(DecisionLogEntry.forEmail(id)).anySatisfy(row -> assertThat(row.output)
                    .contains("\"edited\":true"));
        });
    }

    @Test
    void theOwnerAnswersAnEscalatedEmailFromThePanel() {
        long id = escalated("Salle", "Dans quelle salle ?", "Inès");
        Session s = login();
        assertThat(s.page).contains("Write the reply yourself");

        assertThat(s.post("/panel/escalations/" + id + "/reply", Map.of("body", "  \u202E \n ")).header("Location"))
                .contains("msg=bad_reply");
        assertThat(status(id)).isEqualTo(EmailStatus.ESCALATED);

        assertThat(s.post("/panel/escalations/" + id + "/reply",
                Map.of("body", "Bonjour,\r\n\r\nSalle B12.\u202E")).header("Location")).contains("msg=sent_edited");
        assertThat(status(id)).isEqualTo(EmailStatus.SENT);
        assertThat(QuarkusTransaction.requiringNew().call(() -> OutboxEntity.<OutboxEntity>find("email.id", id)
                .firstResult().body)).isEqualTo("Bonjour,\n\nSalle B12.");
    }

    @Test
    void theWriterIsRetriedOnlyWhenEveryAnswerIsThere() {
        long missing = escalated("Exam", "When?", "Student");
        long complete = escalatedWithFilledSlot();
        Session s = login();

        assertThat(s.post("/panel/escalations/" + missing + "/retry", Map.of()).header("Location"))
                .contains("msg=nothing");
        assertThat(status(missing)).isEqualTo(EmailStatus.ESCALATED);

        assertThat(s.post("/panel/escalations/" + complete + "/retry", Map.of()).header("Location"))
                .contains("msg=retried");
        assertThat(status(complete)).isEqualTo(EmailStatus.READY);
    }

    @Test
    void anOwnerReviewCanOnlyApproveTheOwnersOwnText() {
        long id = savedDraft();
        // the database refuses an "owner" approval of a model's draft: the owner must have written or edited it
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            ReviewEntity r = new ReviewEntity();
            r.email = EmailEntity.findById(id);
            r.draft = DraftEntity.currentFor(id).orElseThrow();
            r.passed = true;
            r.reviewedBy = Author.OWNER;
            r.createdAt = Instant.now();
            r.persist();
            ReviewEntity.flush();
        })).hasStackTraceContaining("an owner review approves a draft the owner wrote");
    }

    @Test
    void escalationReasonsAreWrittenForPeople() {
        long id = escalated("Ma note", "Quelle note ?", "Adam");
        QuarkusTransaction.requiringNew().run(() -> DecisionLogEntry.transition(id, "router", EmailStatus.CLASSIFIED,
                EmailStatus.ESCALATED, "{\"route\":\"not_automatable\"}", null, null, null));

        String page = login().page;

        assertThat(page).contains("Personal result (such as a grade): never answered automatically")
                .doesNotContain("{&quot;route&quot;");
    }

    @Test
    void theOwnerSeesWhatTheAgentKnowsAndCanMakeItForget() {
        long factId = fact("Room B12");
        assertThat(given().redirects().follow(false).get("/panel/memory").statusCode()).isEqualTo(302);
        Session s = login();
        String page = given().cookie("quarkus-credential", s.credential).get("/panel/memory").asString();
        assertThat(page).contains("What the agent knows").contains("Room B12");

        assertThat(s.post("/panel/memory/facts/" + factId + "/forget", Map.of()).header("Location"))
                .endsWith("/panel/memory?msg=forgot");
        assertThat(s.post("/panel/memory/facts/" + factId + "/forget", Map.of()).header("Location"))
                .endsWith("/panel/memory?msg=nothing");
        QuarkusTransaction.requiringNew().run(() -> {
            assertThat(FactEntity.<FactEntity>findById(factId).active).isFalse();
            assertThat(DecisionLogEntry.<DecisionLogEntry>list("agent = 'owner' and output like ?1",
                    "%\"forgot_fact\":" + factId + "}%")).hasSize(1);
        });
    }

    @Test
    void theActivityPageTellsTheStoryInWords() {
        long id = escalated("<b>Ma note</b>", "Quelle note ?", "Adam");
        QuarkusTransaction.requiringNew().run(() -> DecisionLogEntry.transition(id, "router", EmailStatus.CLASSIFIED,
                EmailStatus.ESCALATED, "{\"route\":\"not_automatable\"}", null, null, null));

        String page = given().cookie("quarkus-credential", login().credential).get("/panel/activity").asString();

        assertThat(page).contains("Router").contains("Handed to you: personal result (such as a grade)")
                .contains("&lt;b&gt;Ma note&lt;/b&gt;").doesNotContain("<b>Ma note</b>");
    }

    @Test
    void theMetricsPageIsTheOwnersToo() {
        assertThat(given().redirects().follow(false).get("/panel/metrics").statusCode()).isEqualTo(302);
        Response r = given().cookie("quarkus-credential", login().credential).get("/panel/metrics");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.asString()).contains("Auto-send readiness per slot type").contains("EXAM_DATE")
                .contains("Memory reuse");
    }

    @Test
    void theRepresentativeIsTheMostTypicalReply() {
        PanelQueries.DraftView a = draft(1, "EN", 100, List.of());
        PanelQueries.DraftView b = draft(2, "EN", 140, List.of());
        PanelQueries.DraftView c = draft(3, "EN", 400, List.of());
        PanelQueries.DraftView fr = draft(4, "FR", 140, List.of());
        PanelQueries.DraftView odd = draft(5, "EN", 140, List.of("writer_retry"));

        PanelQueries.DraftGroup g = PanelQueries.group(9L, "card", List.of(a, b, c, fr, odd));

        assertThat(g.representative()).isEqualTo(b); // majority language, median length
        assertThat(g.outliers()).containsExactly(odd); // never in "approve all"
        assertThat(g.sendable()).isEqualTo(4);
    }

    // --- HTTP helpers ---

    record Session(String credential, String csrfCookie, String token, String page) {

        Response post(String path, Map<String, String> form) {
            // UTF-8, like a browser: RestAssured's default for forms is ISO-8859-1
            return given().cookie("quarkus-credential", credential).cookie("csrf-token", csrfCookie)
                    .redirects().follow(false).contentType("application/x-www-form-urlencoded; charset=UTF-8")
                    .formParams(form).formParam("csrf-token", token).post(path);
        }
    }

    private static final Pattern TOKEN = Pattern.compile("name=\"csrf-token\" value=\"([^\"]+)\"");

    Session login() {
        String credential = sessionCookie(OwnerProfile.PASSWORD);
        assertThat(credential).isNotNull();
        Response panel = given().cookie("quarkus-credential", credential).get("/panel");
        assertThat(panel.statusCode()).isEqualTo(200);
        String page = panel.asString();
        Matcher m = TOKEN.matcher(page);
        return new Session(credential, panel.cookie("csrf-token"), m.find() ? m.group(1) : null, page);
    }

    static String sessionCookie(String password) {
        return loginStatus(password);
    }

    static String loginStatus(String password) {
        Response r = given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
                .formParam("j_username", OwnerProfile.USERNAME).formParam("j_password", password)
                .post("/j_security_check");
        return r.cookie("quarkus-credential");
    }

    // --- data helpers ---

    static PanelQueries.DraftView draft(long id, String lang, int length, List<String> outliers) {
        return new PanelQueries.DraftView(id, "s" + id, null, "subj", "q", "x".repeat(length), lang, "", outliers, List.of(),
                "Student agent");
    }

    long escalated(String subject, String body, String name) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EmailEntity e = email(EmailStatus.ESCALATED);
            e.subject = subject;
            e.body = body;
            e.senderName = name;
            e.persist();
            return e.id;
        });
    }

    /** An escalated email whose only slot is filled: the writer could try again. */
    long escalatedWithFilledSlot() {
        return QuarkusTransaction.requiringNew().call(() -> {
            EmailEntity e = email(EmailStatus.ESCALATED);
            e.persist();
            SlotEntity s = new SlotEntity();
            s.email = e;
            s.type = SlotType.EXAM_DATE;
            s.entity = "ml exam";
            s.owner = SlotOwner.USER;
            s.status = SlotStatus.FILLED;
            s.value = "2027-06-03";
            s.source = "card:1";
            s.persist();
            e.slots.add(s);
            return e.id;
        });
    }

    /** An active, shareable fact with a key no other test uses. */
    long fact(String value) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FactEntity f = new FactEntity();
            f.agentId = "student";
            f.slotType = SlotType.EXAM_ROOM;
            f.entityKey = "exam " + UUID.randomUUID().toString().substring(0, 8);
            f.scopeProgram = "M2";
            f.scopeTrack = "IA";
            f.scopeYear = "2026-2027";
            f.value = value;
            f.shareable = true;
            f.source = "card:0 answered by owner";
            f.createdAt = Instant.now();
            f.persist();
            return f.id;
        });
    }

    /** {card id, email id}: one waiting email on a personal card. */
    long[] cardWithWaitingEmail() {
        return QuarkusTransaction.requiringNew().call(() -> {
            InputRequestEntity c = new InputRequestEntity();
            c.agentId = "student";
            c.slotType = SlotType.EXAM_DATE;
            c.entityKey = "exam " + UUID.randomUUID().toString().substring(0, 8);
            c.personal = true;
            c.question = "When is this exam?";
            c.fieldType = FieldType.DATE;
            c.status = ClusterStatus.WAITING_INPUT;
            c.memberCount = 1;
            c.createdAt = Instant.now();
            c.lastActivity = Instant.now();
            c.persist();
            EmailEntity e = email(EmailStatus.AWAITING_INPUT);
            e.persist();
            SlotEntity s = new SlotEntity();
            s.email = e;
            s.type = SlotType.EXAM_DATE;
            s.entity = c.entityKey;
            s.owner = SlotOwner.USER;
            s.requestId = c.id;
            s.groupedBy = GroupedBy.PERSONAL;
            s.persist();
            return new long[] {c.id, e.id};
        });
    }

    /** A DRAFT_SAVED email with its current draft and a passed review, as the dispatcher leaves it. */
    long savedDraft() {
        return QuarkusTransaction.requiringNew().call(() -> {
            EmailEntity e = email(EmailStatus.DRAFT_SAVED);
            e.persist();
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
            return e.id;
        });
    }

    static EmailEntity email(EmailStatus status) {
        EmailEntity e = new EmailEntity();
        e.agentId = "student";
        e.messageId = "<pn-" + UUID.randomUUID() + "@test>";
        e.sender = "pn-" + UUID.randomUUID().toString().substring(0, 8) + "@student.univ.example";
        e.subject = "Exam";
        e.body = "When?";
        e.language = Language.EN;
        e.receivedAt = Instant.now().minus(Duration.ofMinutes(5));
        e.status = status;
        return e;
    }

    static EmailStatus status(long id) {
        return QuarkusTransaction.requiringNew().call(() -> EmailEntity.<EmailEntity>findById(id).status);
    }

    static long version(long cardId) {
        return QuarkusTransaction.requiringNew().call(() -> InputRequestEntity.<InputRequestEntity>findById(cardId)
                .version);
    }
}
