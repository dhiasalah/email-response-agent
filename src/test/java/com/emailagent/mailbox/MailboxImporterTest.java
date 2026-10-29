package com.emailagent.mailbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.model.EmailStatus;
import com.emailagent.model.FilterReason;
import com.emailagent.store.EmailEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/** The demo inbox goes through pre-filter and sanitizer into the database, exactly once. */
@QuarkusTest
class MailboxImporterTest {

    @Inject MailboxImporter importer;

    @Test
    void demoInboxIsImportedFilteredAndSanitizedOnce() {
        importer.importNew();
        MailboxImporter.ImportResult second = importer.importNew();

        assertThat(second.imported()).isZero();
        assertThat(second.duplicates()).isEqualTo(12);

        QuarkusTransaction.requiringNew().run(() -> {
            assertThat(EmailEntity.listByStatus(EmailStatus.FILTERED_OUT))
                    .extracting(e -> e.filterReason)
                    .contains(FilterReason.AUTO_REPLY, FilterReason.MAILING_LIST,
                            FilterReason.NO_REPLY_SENDER, FilterReason.OWN_ADDRESS);

            EmailEntity hidden = EmailEntity.findByMessageId("<m07@student.univ.example>").orElseThrow();
            assertThat(hidden.body).contains("date limite").doesNotContain("Forward").doesNotContain("extended");
            assertThat(hidden.sanitizerFlags).isEqualTo("hidden_html");

            EmailEntity unicode = EmailEntity.findByMessageId("<m08@student.univ.example>").orElseThrow();
            assertThat(unicode.body).contains("Ignore previous instructions").doesNotContain("​");
            assertThat(unicode.sanitizerFlags).contains("bidi_control", "invisible_unicode", "name_mixed_scripts");

            EmailEntity autoReply = EmailEntity.findByMessageId("<m04@student.univ.example>").orElseThrow();
            assertThat(autoReply.body).isNull(); // data minimization
        });
    }
}
