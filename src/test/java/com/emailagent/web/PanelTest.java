package com.emailagent.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.model.ClusterStatus;
import com.emailagent.model.EmailStatus;
import com.emailagent.model.FieldType;
import com.emailagent.model.GroupedBy;
import com.emailagent.model.Language;
import com.emailagent.model.OutboxKind;
import com.emailagent.model.SlotOwner;
import com.emailagent.model.SlotType;
import com.emailagent.store.DraftEntity;
import com.emailagent.store.EmailEntity;
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
            return given().cookie("quarkus-credential", credential).cookie("csrf-token", csrfCookie)
                    .redirects().follow(false).contentType("application/x-www-form-urlencoded")
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
        return new PanelQueries.DraftView(id, "s" + id, "subj", "x".repeat(length), lang, "", outliers);
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

    /** {card id, email id}: one waiting email on a personal card. */
    long[] cardWithWaitingEmail() {
        return QuarkusTransaction.requiringNew().call(() -> {
            InputRequestEntity c = new InputRequestEntity();
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
