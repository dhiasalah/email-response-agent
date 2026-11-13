package com.emailagent.mailbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.model.EmailStatus;
import com.emailagent.model.IncomingEmail;
import com.emailagent.store.EmailEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The importer with sender authentication required, as in Gmail mode: an unauthenticated From is stored with its flag,
 * and a forged answer to a clarification marks the email it merges into (design doc §6 thread rule, §9).
 */
@QuarkusTest
@TestProfile(SenderAuthImportTest.RequireSenderAuth.class)
class SenderAuthImportTest {

    public static class RequireSenderAuth implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("emailagent.require-sender-auth", "true");
        }
    }

    static final String LINA = "lina.benali@student.univ.example";
    static final String GMAIL_PASS = "mx.google.com; dkim=pass header.i=@student.univ.example; "
            + "dmarc=pass (p=REJECT) header.from=student.univ.example";

    @Inject MailboxImporter importer;

    @Test
    void anUnauthenticatedSenderIsStoredWithItsFlag() {
        String forged = "<forged-" + UUID.randomUUID() + "@x>";
        String real = "<real-" + UUID.randomUUID() + "@x>";

        importer.importNew(List.of(incoming(forged, null, Map.of()),
                incoming(real, null, Map.of("Authentication-Results", GMAIL_PASS))));

        assertThat(flags(forged)).contains("sender_unverified");
        assertThat(flags(real)).doesNotContain("sender_unverified");
    }

    @Test
    void aForgedAnswerMarksTheEmailItJoins() {
        String thread = "t-" + UUID.randomUUID();
        long waiting = QuarkusTransaction.requiringNew().call(() -> {
            EmailEntity e = new EmailEntity();
            e.messageId = "<wait-" + UUID.randomUUID() + "@test>";
            e.threadId = thread;
            e.sender = LINA;
            e.subject = "Exam";
            e.body = "When is the exam?";
            e.receivedAt = Instant.now();
            e.status = EmailStatus.AWAITING_SENDER;
            e.persist();
            return e.id;
        });

        MailboxImporter.ImportResult r = importer.importNew(List.of(
                incoming("<answer-" + UUID.randomUUID() + "@x>", thread, Map.of())));

        assertThat(r.merged()).isEqualTo(1);
        assertThat(QuarkusTransaction.requiringNew().call(() -> EmailEntity.<EmailEntity>findById(waiting)
                .sanitizerFlags)).contains("sender_unverified");
    }

    private static IncomingEmail incoming(String messageId, String thread, Map<String, String> headers) {
        return new IncomingEmail(messageId, thread, LINA, "Lina", "Re: Exam", "The machine learning one.", null,
                Instant.now(), headers);
    }

    private static String flags(String messageId) {
        String f = QuarkusTransaction.requiringNew().call(() -> EmailEntity.findByMessageId(messageId).orElseThrow()
                .sanitizerFlags);
        return f == null ? "" : f;
    }
}
