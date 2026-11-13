package com.emailagent.mailbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.emailagent.model.SenderRole;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/** A roster address someone else may have written in the From header gets no scope and no role (design doc §8.1). */
@QuarkusTest
class SenderDirectoryAuthTest {

    @Inject SenderDirectory directory;

    @Test
    void unverifiedSenderHasNoScopeAndNoRole() {
        String lina = "lina.benali@student.univ.example"; // in directory/senders.json, M2 IA
        assertThat(directory.scopeOf(lina, "hidden_html,sender_unverified").isKnown()).isFalse();
        assertThat(directory.roleOf(lina, "sender_unverified")).isEqualTo(SenderRole.UNKNOWN);
        assertThat(directory.scopeOf(lina, "hidden_html").isKnown()).isTrue();
        assertThat(directory.scopeOf(lina, null).isKnown()).isTrue();
    }
}
