package com.emailagent.mailbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.config.AppConfig;
import com.emailagent.model.IncomingEmail;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void theDefaultFollowsTheMailboxActuallyRunning() {
        // the mailbox bean is chosen at build time: the default must follow it, not a runtime MAILBOX variable
        assertThat(MailboxImporter.senderAuthRequired(Optional.empty(), true)).isTrue();
        assertThat(MailboxImporter.senderAuthRequired(Optional.empty(), false)).isFalse();
        assertThat(MailboxImporter.senderAuthRequired(Optional.of(true), false)).isTrue();
    }

    @Test
    void onlyTheRealMailboxCarriesServerVerdicts(@TempDir Path dir) {
        AppConfig config = config(dir);
        assertThat(new GmailMailbox(new ObjectMapper(), config).authenticatesSenders()).isTrue();
        assertThat(new MockMailbox(new ObjectMapper(), config).authenticatesSenders()).isFalse();
    }

    /** Just the settings the two mailbox constructors read. */
    static AppConfig config(Path dir) {
        return (AppConfig) Proxy.newProxyInstance(AppConfig.class.getClassLoader(), new Class<?>[] {AppConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "ownerAddress" -> "owner@gmail.com";
                    case "gmailCredentialsDir" -> dir.toString();
                    case "gmailAllowedSenders" -> Optional.of(List.of("lina.benali@student.univ.example"));
                    case "gmailLookbackDays" -> 14;
                    case "mailboxFixture" -> "mailbox/inbox.json";
                    case "mailboxSentFixture" -> "mailbox/sent.json";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void authenticatedMailIsNotFlagged() {
        assertThat(MailboxImporter.authFlag(mail("mx.google.com; dmarc=pass header.from=student.univ.example"), true,
                "mx.google.com")).isEmpty();
    }
}
