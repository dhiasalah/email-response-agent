package com.emailagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.logging.Level;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

/** Hardening (design doc §13): no address in the logs, no model outside this machine. */
class HardeningConfigTest {

    @Test
    void addressesAreRedactedAfterFormatting() {
        ExtLogRecord record = new ExtLogRecord(Level.WARNING, "rejected message %s (%s)",
                ExtLogRecord.FormatStyle.PRINTF, getClass().getName());
        record.setParameters(new Object[] {"<lina.benali@student.univ.example>", "IllegalStateException"});

        assertThat(new PiiLogFilter().isLoggable(record)).isTrue(); // redacted, never dropped
        assertThat(record.getFormattedMessage()).isEqualTo("rejected message <[email]> (IllegalStateException)");
        assertThat(PiiLogFilter.redact("email 42 ESCALATED -> CLOSED")).isEqualTo("email 42 ESCALATED -> CLOSED");
    }

    @Test
    void theModelMustRunOnThisMachine() {
        assertThatCode(() -> StartupConfigCheck.checkLocal("http://127.0.0.1:11434")).doesNotThrowAnyException();
        assertThatCode(() -> StartupConfigCheck.checkLocal("http://localhost:11434")).doesNotThrowAnyException();
        assertThatThrownBy(() -> StartupConfigCheck.checkLocal("https://api.some-cloud.example/v1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("allow-remote-llm");
        assertThatThrownBy(() -> StartupConfigCheck.checkLocal("http://127.0.0.1.evil.example:11434"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StartupConfigCheck.checkLocal("not a url")).isInstanceOf(IllegalStateException.class);
    }
}
