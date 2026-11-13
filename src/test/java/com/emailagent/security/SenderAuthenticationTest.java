package com.emailagent.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.mailbox.IncomingEmail;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The From header is free text; only the receiving server's verdict ties it to a real mailbox (design doc §9, §15).
 * Anything short of a DMARC pass or an aligned DKIM pass, from our own server, is not authenticated.
 */
class SenderAuthenticationTest {

    static final String FROM = "lina.benali@student.univ.example";
    static final String GMAIL = "mx.google.com";

    static IncomingEmail mail(String from, String authResults) {
        return new IncomingEmail("<id>", "t", from, "Lina", "Notes du S2", "body", null, Instant.now(),
                authResults == null ? Map.of() : Map.of("Authentication-Results", authResults));
    }

    private static boolean auth(String header) {
        return SenderAuthentication.isAuthenticated(mail(FROM, header), GMAIL);
    }

    @Test
    void gmailDmarcPassWithMatchingFromIsAuthenticated() {
        assertThat(auth("mx.google.com; dkim=pass header.i=@student.univ.example header.s=s1 header.b=abc; "
                + "spf=pass (google.com: domain of " + FROM + " designates 1.2.3.4 as permitted sender) smtp.mailfrom="
                + FROM + "; dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=student.univ.example")).isTrue();
    }

    @Test
    void dmarcPassForAnotherDomainIsNot() {
        assertThat(auth("mx.google.com; dmarc=pass header.from=evil.example")).isFalse();
    }

    @Test
    void missingHeaderIsNot() {
        assertThat(auth(null)).isFalse();
    }

    @Test
    void foreignAuthServIdIsNot() {
        assertThat(auth("attacker.example; dmarc=pass header.from=student.univ.example")).isFalse();
    }

    @Test
    void alignedParentDkimWithoutDmarcIsAuthenticated() {
        assertThat(auth("mx.google.com; dkim=pass header.i=@univ.example; spf=pass smtp.mailfrom=" + FROM
                + "; dmarc=none")).isTrue();
    }

    @Test
    void spfPassAloneIsNot() {
        // SPF checks the envelope sender, not the From the student reads
        assertThat(auth("mx.google.com; spf=pass smtp.mailfrom=" + FROM + "; dmarc=none")).isFalse();
    }

    @Test
    void dmarcFailWinsOverDkimPass() {
        assertThat(auth("mx.google.com; dkim=pass header.i=@student.univ.example; dmarc=fail (p=NONE) "
                + "header.from=student.univ.example")).isFalse();
    }

    @Test
    void commentsCannotForgeAResult() {
        assertThat(auth("mx.google.com; spf=softfail (google.com: dmarc=pass header.from=student.univ.example) "
                + "smtp.mailfrom=x@evil.example")).isFalse();
    }

    @Test
    void lookAlikeDomainIsNotAligned() {
        assertThat(auth("mx.google.com; dkim=pass header.i=@niv.example; dmarc=none")).isFalse();
    }

    @Test
    void quotedEnvelopeSenderCannotInjectAResult() {
        // the sender chooses the envelope local part and Gmail echoes it inside its own header
        assertThat(auth("mx.google.com; spf=pass smtp.mailfrom=\"x;dmarc=pass header.from=student.univ.example x\""
                + "@evil.example")).isFalse();
        assertThat(auth("mx.google.com; spf=pass smtp.mailfrom=\"x;dmarc=pass(a)header.from=student.univ.example(b)\""
                + "@evil.example")).isFalse();
        assertThat(auth("mx.google.com; dkim=pass header.i=\"a;dkim=pass header.d=univ.example x\"@evil.example"))
                .isFalse();
    }

    @Test
    void commentGluedToAValueIsRejected() {
        // a HELO name can't hold spaces, so an injected result needs comments as separators: Gmail never glues them
        assertThat(auth("mx.google.com; spf=none smtp.helo=x;dmarc=pass(a)header.from=student.univ.example(b)"))
                .isFalse();
    }

    @Test
    void unbalancedQuotesOrCommentsAreRejected() {
        assertThat(auth("mx.google.com; dmarc=pass header.from=student.univ.example; spf=pass "
                + "smtp.mailfrom=\"x@evil.example")).isFalse();
        assertThat(auth("mx.google.com; dmarc=pass (p=NONE header.from=student.univ.example")).isFalse();
        assertThat(auth("mx.google.com; dmarc=pass header.from=student.univ.example) x")).isFalse();
    }

    @Test
    void aQuotedDomainStillCounts() {
        assertThat(auth("mx.google.com; dmarc=pass header.from=\"student.univ.example\"")).isTrue();
    }

    @Test
    void foldedMixedCaseHeaderIsParsed() {
        assertThat(auth("MX.Google.com;\r\n\tDMARC=PASS (p=NONE)\r\n\theader.from=Student.Univ.Example")).isTrue();
    }
}
