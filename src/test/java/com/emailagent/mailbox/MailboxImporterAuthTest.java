package com.emailagent.mailbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The importer marks mail whose sender the receiving server didn't authenticate, when the mailbox requires it. */
class MailboxImporterAuthTest {

    static IncomingEmail mail(String authResults) {
        return new IncomingEmail("<id>", "t", "lina.benali@student.univ.example", "Lina", "Notes du S2", "body", null,
                Instant.now(), authResults == null ? Map.of() : Map.of("Authentication-Results", authResults));
    }

    @Test
    void unauthenticatedMailIsFlaggedWhenRequired() {
        assertThat(MailboxImporter.authFlag(mail(null), true, "mx.google.com")).contains("sender_unverified");
    }

    @Test
    void nothingIsFlaggedWhenNotRequired() {
        assertThat(MailboxImporter.authFlag(mail(null), false, "mx.google.com")).isEmpty();
    }

    @Test
    void authenticatedMailIsNotFlagged() {
        assertThat(MailboxImporter.authFlag(mail("mx.google.com; dmarc=pass header.from=student.univ.example"), true,
                "mx.google.com")).isEmpty();
    }
}
