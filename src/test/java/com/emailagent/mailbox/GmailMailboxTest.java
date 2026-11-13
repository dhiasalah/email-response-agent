package com.emailagent.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.emailagent.config.SendMode;
import com.emailagent.config.StartupConfigCheck;
import com.emailagent.model.IncomingEmail;
import com.emailagent.model.OutboxKind;
import com.emailagent.security.SenderAuthentication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Gmail mailbox without the network (design doc §10, §11): Gmail's JSON to our IncomingEmail, our reply to an RFC
 * 822 draft, and the guarantees that make Gmail mode safe: no send endpoint in the code, drafts-only configuration.
 */
class GmailMailboxTest {

    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aGmailMessageBecomesAnIncomingEmail() throws Exception {
        JsonNode msg = mapper.readTree("""
                {"id":"18f0","threadId":"t42","internalDate":"1793300000000",
                 "payload":{"mimeType":"multipart/alternative",
                   "headers":[{"name":"From","value":"\\"Abdel K.\\" <Abdel.K@Example.org>"},
                              {"name":"Subject","value":"Projet SMA"},
                              {"name":"Message-ID","value":"<abc@mail.example.org>"},
                              {"name":"Auto-Submitted","value":"no"}],
                   "parts":[{"mimeType":"text/plain","body":{"data":"%s"}},
                            {"mimeType":"text/html","body":{"data":"%s"}}]}}""".formatted(
                b64("Bonjour, quand rendre le projet ?"), b64("<p>Bonjour, <b>quand</b> rendre le projet ?</p>")));

        IncomingEmail e = GmailMailbox.toIncoming(msg);

        assertThat(e.from()).isEqualTo("abdel.k@example.org"); // exact, lower case: what the allow list compares
        assertThat(e.fromName()).isEqualTo("Abdel K.");
        assertThat(e.messageId()).isEqualTo("<abc@mail.example.org>");
        assertThat(e.threadId()).isEqualTo("t42");
        assertThat(e.bodyText()).isEqualTo("Bonjour, quand rendre le projet ?");
        assertThat(e.bodyHtml()).contains("<b>quand</b>"); // the sanitizer handles it, as for any mail
        assertThat(e.header("Auto-Submitted")).isEqualTo("no"); // headers reach the pre-filter
    }

    @Test
    void aReplyBecomesADraftInItsThreadWithNoInjectableHeader() {
        OutgoingMessage reply = new OutgoingMessage(OutboxKind.DRAFT, "abdel.k@example.org",
                "Re: Projet SMA\r\nBcc: everyone@example.org", "Bonjour,\n\nLe projet est à rendre le 20 novembre.",
                "<abc@mail.example.org>", "<reply-1-2-draft@gmail.com>");

        String raw = new String(Base64.getUrlDecoder().decode(GmailMailbox.draftRaw(reply, "owner@gmail.com")),
                StandardCharsets.UTF_8);

        assertThat(raw).contains("To: abdel.k@example.org\r\n").contains("In-Reply-To: <abc@mail.example.org>\r\n")
                .contains("References: <abc@mail.example.org>\r\n").contains("X-Email-Agent: draft\r\n")
                .doesNotContain("\r\nBcc:"); // the line break is gone: one Subject header, no Bcc header
        String body = raw.substring(raw.indexOf("\r\n\r\n") + 4);
        assertThat(new String(Base64.getMimeDecoder().decode(body), StandardCharsets.UTF_8))
                .isEqualTo("Bonjour,\n\nLe projet est à rendre le 20 novembre.");
        assertThat(GmailMailbox.encodeHeader("Réponse")).startsWith("=?UTF-8?B?");
        assertThat(GmailMailbox.encodeHeader("Reply")).isEqualTo("Reply");
    }

    @Test
    void forgedHeaderBelowGmailsIsIgnored() throws Exception {
        // a sender can write any header into the message; Gmail adds its own verdict on top, and only that one counts
        JsonNode msg = mapper.readTree("""
                {"id":"18f1","threadId":"t43","internalDate":"1793300000000",
                 "payload":{"mimeType":"text/plain",
                   "headers":[{"name":"Authentication-Results",
                               "value":"mx.google.com; dmarc=fail header.from=student.univ.example"},
                              {"name":"From","value":"Lina <lina.benali@student.univ.example>"},
                              {"name":"Authentication-Results",
                               "value":"mx.google.com; dmarc=pass header.from=student.univ.example"},
                              {"name":"Subject","value":"Notes"}],
                   "body":{"data":"%s"}}}""".formatted(b64("Bonjour")));

        assertThat(SenderAuthentication.isAuthenticated(GmailMailbox.toIncoming(msg), "mx.google.com")).isFalse();
    }

    @Test
    void noCodeInTheMailboxCanSendMail() throws Exception {
        // gmail.modify would allow sending: "drafts only" is guaranteed by the absence of these calls
        try (Stream<Path> files = Files.list(Path.of("src/main/java/com/emailagent/mailbox"))) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String code = read(p);
                assertThat(code).as(p.toString()).doesNotContain("messages/send").doesNotContain("drafts/send")
                        .doesNotContain("/send\"").doesNotContainIgnoringCase("smtp");
            });
        }
    }

    @Test
    void gmailModeRefusesAutomaticSending() {
        assertThatCode(() -> StartupConfigCheck.checkGmailMode("gmail", SendMode.DRAFT, false))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> StartupConfigCheck.checkGmailMode("gmail", SendMode.AUTO, false))
                .hasMessageContaining("only creates drafts");
        assertThatThrownBy(() -> StartupConfigCheck.checkGmailMode("gmail", SendMode.DRAFT, true))
                .hasMessageContaining("only creates drafts");
        assertThatCode(() -> StartupConfigCheck.checkGmailMode("mock", SendMode.AUTO, true))
                .doesNotThrowAnyException();
    }

    @Test
    void aRosterCanLiveOutsideTheProject(@TempDir Path dir) throws Exception {
        Path roster = dir.resolve("senders.json");
        Files.writeString(roster, "[]");
        try (InputStream in = SenderDirectory.open("file:" + roster)) {
            assertThat(in).isNotNull();
        }
        assertThat(SenderDirectory.open("file:" + dir.resolve("missing.json"))).isNull();
        assertThat(GmailMailbox.address("Name <A@B.org>")).isEqualTo("a@b.org");
        assertThat(GmailMailbox.address("plain@b.org")).isEqualTo("plain@b.org");
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
